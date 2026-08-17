package ceui.pixiv.ui.novel.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import com.blankj.utilcode.util.BarUtils
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction

/**
 * 本地小说书库 —— 用户给一个文件夹，按文件夹分系列/作者、按文件名排序当列表，
 * 标题直接抓文件名。不依赖任何 pixiv 数据结构，纯离线读 txt。
 *
 * 浏览状态全在 [LocalLibraryViewModel]；本类只负责选目录（SAF picker）和渲染。
 * - 选根目录走 SAF（ACTION_OPEN_DOCUMENT_TREE，显式 setComponent 兜 vivo 重定向坑），
 *   根 URI 落 [LocalLibraryStore]（MMKV 设备本地，不进 Settings 跨设备同步）。
 * - 点 txt 进 V3 阅读器（[ceui.pixiv.ui.novel.reader.NovelReaderV3Fragment] 的本地分支）。
 */
class LocalLibraryFragment : Fragment(R.layout.fragment_local_library) {

    private val viewModel: LocalLibraryViewModel by viewModels()

    private lateinit var toolbar: Toolbar
    private lateinit var breadcrumb: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var btnPick: Button
    private var clearItem: MenuItem? = null

    private val adapter = LocalAdapter { entry ->
        if (entry.isDir) viewModel.drillInto(entry) else openFile(entry)
    }

    private val pickRootLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Common.showToast(getString(R.string.local_novel_pick_failed, e.message ?: ""))
            return@registerForActivityResult
        }
        LocalLibraryStore.rootUri = uri.toString()
        viewModel.openRoot(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar = view.findViewById(R.id.toolbar)
        // BaseActivity 开了 EdgeToEdge，状态栏 inset 走 BarUtils 手动 padding，
        // 不用 fitsSystemWindows（会把 status + nav 两个 inset 都套上）。
        toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        toolbar.setNavigationOnClickListener { if (!viewModel.goUp()) activity?.finish() }
        toolbar.menu.add(Menu.NONE, MENU_PICK, 0, getString(R.string.local_novel_pick_folder))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        clearItem = toolbar.menu.add(Menu.NONE, MENU_CLEAR, 1, getString(R.string.local_novel_clear)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isVisible = false // 没选过文件夹时无所谓清空，render 里按状态打开
        }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_PICK -> { launchPicker(); true }
                MENU_CLEAR -> { showClearConfirm(); true }
                else -> false
            }
        }

        breadcrumb = view.findViewById(R.id.breadcrumb)
        emptyState = view.findViewById(R.id.emptyState)
        emptyText = view.findViewById(R.id.emptyText)
        btnPick = view.findViewById(R.id.btnPick)
        // V3 主按钮：accent 实心 pill，色随当前主题派生（V3Palette）。
        btnPick.background = V3Palette.from(requireContext())
            .pillPrimary(999f * resources.displayMetrics.density)
        btnPick.setOnClickListener { launchPicker() }

        recycler = view.findViewById(R.id.list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!viewModel.goUp()) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        viewModel.startIfNeeded()
    }

    private fun render(state: LocalLibraryViewModel.UiState) {
        when (state) {
            is LocalLibraryViewModel.UiState.NeedRoot -> {
                clearItem?.isVisible = false
                toolbar.title = getString(R.string.local_novel_entry)
                breadcrumb.visibility = View.GONE
                adapter.submitList(emptyList())
                showEmpty(getString(R.string.local_novel_empty_no_root), pickVisible = true)
            }
            is LocalLibraryViewModel.UiState.Browsing -> {
                clearItem?.isVisible = true
                toolbar.title = state.crumbs.lastOrNull()?.name ?: getString(R.string.local_novel_entry)
                breadcrumb.text = state.crumbs.joinToString("  ›  ") { it.name }
                breadcrumb.visibility = if (state.crumbs.size > 1) View.VISIBLE else View.GONE
                adapter.submitList(state.entries)
                if (state.entries.isEmpty()) {
                    showEmpty(getString(R.string.local_novel_empty_folder), pickVisible = false)
                } else {
                    hideEmpty()
                }
            }
        }
    }

    // ---- SAF 选根目录 --------------------------------------------------------

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        // vivo/iQOO 非 debuggable 包上 implicit ACTION_OPEN_DOCUMENT_TREE 会被悄悄
        // 重定向到 LAUNCHER（见 BaseActivity.launchSafTreePicker）。显式钉死到真正的
        // DocumentsUI 兜掉这个坑。
        runCatching { intent.resolveActivity(requireContext().packageManager) }
            .getOrNull()?.let { intent.component = it }
        try {
            pickRootLauncher.launch(intent)
        } catch (e: Exception) {
            Common.showToast(getString(R.string.local_novel_pick_failed, e.message ?: ""))
        }
    }

    /** 清空书架前确认。文案明确「不会删文件」，避免用户误以为要删小说。仅 QMUIDialog。 */
    private fun showClearConfirm() {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        QMUIDialog.MessageDialogBuilder(act)
            .setTitle(R.string.local_novel_clear_confirm_title)
            .setMessage(R.string.local_novel_clear_confirm_msg)
            .setSkinManager(QMUISkinManager.defaultInstance(act))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.sure, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                viewModel.clearLibrary()
            }
            .show()
    }

    // ---- 打开正文 ------------------------------------------------------------

    private fun openFile(entry: LocalLibraryViewModel.Entry) {
        val tree = viewModel.currentTree() ?: return
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.docId)
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说正文")
            putExtra(Params.LOCAL_TXT_URI, fileUri.toString())
            putExtra(Params.LOCAL_TXT_TITLE, titleOf(entry.name))
            putExtra(Params.LOCAL_TXT_KEY, buildRelPath(entry.name))
        }
        startActivity(intent)
    }

    /** 书库内相对路径（去掉根目录名），作为进度/标注/书签的稳定 key。 */
    private fun buildRelPath(fileName: String): String {
        val dirs = viewModel.currentCrumbs().drop(1).joinToString("/") { it.name }
        return if (dirs.isEmpty()) fileName else "$dirs/$fileName"
    }

    // ---- 视图状态 ------------------------------------------------------------

    private fun showEmpty(msg: String, pickVisible: Boolean) {
        emptyText.text = msg
        btnPick.visibility = if (pickVisible) View.VISIBLE else View.GONE
        emptyState.visibility = View.VISIBLE
        recycler.visibility = View.GONE
    }

    private fun hideEmpty() {
        emptyState.visibility = View.GONE
        recycler.visibility = View.VISIBLE
    }

    companion object {
        private const val MENU_PICK = 1
        private const val MENU_CLEAR = 2
    }
}

/** 去扩展名的展示标题；没有点就原样返回。 */
private fun titleOf(fileName: String): String = fileName.substringBeforeLast('.', fileName)

private val LOCAL_DIFF = object : DiffUtil.ItemCallback<LocalLibraryViewModel.Entry>() {
    override fun areItemsTheSame(a: LocalLibraryViewModel.Entry, b: LocalLibraryViewModel.Entry): Boolean =
        a.docId == b.docId
    override fun areContentsTheSame(a: LocalLibraryViewModel.Entry, b: LocalLibraryViewModel.Entry): Boolean =
        a == b
}

private class LocalAdapter(
    private val onClick: (LocalLibraryViewModel.Entry) -> Unit,
) : ListAdapter<LocalLibraryViewModel.Entry, LocalAdapter.VH>(LOCAL_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.cell_local_library_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = getItem(position)
        val ctx = h.itemView.context
        h.icon.setImageResource(
            if (e.isDir) R.drawable.ic_local_folder else R.drawable.ic_baseline_menu_book_24,
        )
        h.title.text = if (e.isDir) e.name else titleOf(e.name)
        h.subtitle.text = if (e.isDir) ctx.getString(R.string.local_novel_folder)
        else Formatter.formatShortFileSize(ctx, e.size)
        h.chevron.visibility = if (e.isDir) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener { onClick(e) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val chevron: ImageView = v.findViewById(R.id.chevron)
    }
}
