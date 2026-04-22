package ceui.pixiv.ui.novel

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.Client
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.NovelMultiSelectReceiver
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.BatchDownloadNovelsTask
import ceui.pixiv.ui.task.FailedNovel
import ceui.pixiv.utils.setOnClick
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 「未归类作品」虚拟系列页面。列表内容 = 作者全部已发布的独立单篇（novel.series == null）。
 * UX 完全沿用 [NovelSeriesFragment] 的模式：
 *  - 右上多选切换按钮
 *  - 底部「下载全部」按钮与多选行（全选 / 下载选中 (N)）互相替换
 *  - 批量下载失败时弹窗列出失败条目
 *
 * 入口：[TemplateActivity] 路由「未归类小说」+ USER_ID。
 */
class UncategorizedNovelsFragment : PixivFragment(R.layout.fragment_pixiv_list),
    NovelMultiSelectReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val userId: Long by lazy { arguments?.getLong(ARG_USER_ID, 0L) ?: 0L }
    private val viewModel by constructVM({ userId }) { uid ->
        UncategorizedNovelsViewModel(uid)
    }

    private var singleDownloadBtn: TextView? = null
    private var multiSelectBar: View? = null
    private var multiSelectDownloadBtn: TextView? = null
    private var multiSelectSelectAllBtn: TextView? = null
    private var topToggleBtn: ImageView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)

        // Set a readable title — the fragment is hosted by TemplateActivity
        // which renders layout_toolbar above the list.
        binding.toolbarLayout.naviTitle.text =
            getString(R.string.uncategorized_novels_title)
        binding.toolbarLayout.naviMore.isVisible = false

        addDownloadAllButton()
        addMultiSelectActionBar()
        addMultiSelectTopToggle()

        viewModel.isMultiSelect.observe(viewLifecycleOwner) { enabled ->
            applyMultiSelectVisibility(enabled)
            binding.listView.adapter?.notifyDataSetChanged()
        }
        viewModel.selectedIds.observe(viewLifecycleOwner) { selected ->
            val count = selected.size
            multiSelectDownloadBtn?.text =
                getString(R.string.download_selected_count, count)
            val allIds = viewModel.allNovelIds()
            val allSelected = allIds.isNotEmpty() && selected.containsAll(allIds)
            multiSelectSelectAllBtn?.text = getString(
                if (allSelected) R.string.deselect_all else R.string.select_all
            )
            binding.listView.adapter?.notifyDataSetChanged()
        }
    }

    private fun addDownloadAllButton() {
        val density = resources.displayMetrics.density
        val btn = TextView(requireContext()).apply {
            text = getString(R.string.download_all_artworks)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * density
                setColor(0xFF2196F3.toInt())
            }
            elevation = 4 * density
            val h = (48 * density).toInt()
            val mx = (20 * density).toInt()
            val my = (12 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h,
            ).apply { setMargins(mx, my, mx, my) }
            setOnClick { launchDownloadAllLoaded() }
        }
        binding.bottomCovered.isVisible = true
        binding.bottomCovered.addView(btn)
        singleDownloadBtn = btn
    }

    private fun addMultiSelectActionBar() {
        val density = resources.displayMetrics.density
        val palette = V3Palette.from(requireContext())

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            val mx = (20 * density).toInt()
            val my = (12 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt(),
            ).apply { setMargins(mx, my, mx, my) }
            visibility = View.GONE
        }

        val selectAll = TextView(requireContext()).apply {
            text = getString(R.string.select_all)
            setTextColor(palette.textAccent)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = palette.pillSecondary(28 * density, (1 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            ).apply { marginEnd = (10 * density).toInt() }
            setOnClick { onClickSelectAllToggle() }
        }

        val download = TextView(requireContext()).apply {
            text = getString(R.string.download_selected_count, 0)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = palette.pillPrimary(28 * density)
            elevation = 4 * density
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f
            )
            setOnClick { launchBatchDownloadSelected() }
        }

        row.addView(selectAll)
        row.addView(download)

        binding.bottomCovered.isVisible = true
        binding.bottomCovered.addView(row)

        multiSelectBar = row
        multiSelectSelectAllBtn = selectAll
        multiSelectDownloadBtn = download
    }

    private fun addMultiSelectTopToggle() {
        val density = resources.displayMetrics.density
        val palette = V3Palette.from(requireContext())

        val toggle = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_checkbox_off)
            setColorFilter(palette.textTag)
            val size = (44 * density).toInt()
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = palette.pillSecondary(
                999f * density, (1 * density).toInt()
            )
            setOnClick {
                val now = viewModel.isMultiSelect.value == true
                viewModel.setMultiSelectMode(!now)
            }
            val statusBarH = com.blankj.utilcode.util.BarUtils.getStatusBarHeight()
            val lp = ConstraintLayout.LayoutParams(size, size).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = statusBarH + (8 * density).toInt()
                marginEnd = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val rootLayout = binding.root as ConstraintLayout
        rootLayout.addView(toggle)
        topToggleBtn = toggle
    }

    private fun applyMultiSelectVisibility(enabled: Boolean) {
        singleDownloadBtn?.isVisible = !enabled
        multiSelectBar?.isVisible = enabled
        val palette = V3Palette.from(requireContext())
        topToggleBtn?.let { tb ->
            if (enabled) {
                tb.setImageResource(R.drawable.ic_check_circle_black_24dp)
                tb.clearColorFilter()
            } else {
                tb.setImageResource(R.drawable.ic_checkbox_off)
                tb.setColorFilter(palette.textTag)
            }
        }
    }

    private fun onClickSelectAllToggle() {
        val selected = viewModel.selectedIds.value.orEmpty()
        val allIds = viewModel.allNovelIds()
        if (allIds.isEmpty()) return
        if (selected.containsAll(allIds)) {
            viewModel.clearSelection()
        } else {
            viewModel.selectAll()
        }
    }

    private fun launchBatchDownloadSelected() {
        val novels = viewModel.selectedNovels()
        if (novels.isEmpty()) {
            ToastUtils.show(getString(R.string.batch_download_no_selection))
            return
        }
        BatchDownloadNovelsTask(
            activity = requireActivity(),
            novels = novels,
            onFinished = { failures -> onBatchDownloadFinished(failures) },
        )
    }

    /**
     * "下载全部" only downloads what's been loaded so far — not what we could
     * theoretically fetch. Rationale: the series download-all path uses
     * FetchAllTask + a cache file to stage the job, but here the source API
     * doesn't filter by "no series", so we'd have to fetch *everything* then
     * filter again. Simpler & less surprising: download the currently
     * filtered set. If the user wants more, they pull-to-refresh / scroll.
     */
    private fun launchDownloadAllLoaded() {
        val allNovels = viewModel.allLoadedNovels()
        if (allNovels.isEmpty()) {
            ToastUtils.show(getString(R.string.batch_download_no_selection))
            return
        }
        BatchDownloadNovelsTask(
            activity = requireActivity(),
            novels = allNovels,
            onFinished = { failures -> onBatchDownloadFinished(failures) },
        )
    }

    private fun onBatchDownloadFinished(failures: List<FailedNovel>) {
        if (!isAdded) return
        if (failures.isEmpty()) {
            ToastUtils.show(getString(R.string.batch_download_all_ok))
            viewModel.setMultiSelectMode(false)
            return
        }
        val msg = failures.joinToString(separator = "\n") { fn ->
            getString(
                R.string.batch_download_failure_line,
                fn.novel.title.orEmpty(),
                fn.reason.orEmpty(),
            )
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.batch_download_some_failed, failures.size))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ── NovelMultiSelectReceiver ────────────────────────────────────
    override fun isNovelMultiSelectMode(): Boolean {
        return viewModel.isMultiSelect.value == true
    }

    override fun isNovelSelected(novelId: Long): Boolean {
        return viewModel.selectedIds.value?.contains(novelId) == true
    }

    override fun onToggleNovelSelection(novelId: Long) {
        viewModel.toggleSelection(novelId)
    }

    override fun onClickUser(id: Long) {
        val intent = Intent(requireContext(), UActivity::class.java).apply {
            putExtra(Params.USER_ID, id.toInt())
        }
        startActivity(intent)
    }

    override fun onClickNovel(novelId: Long) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
            putExtra(Params.NOVEL_ID, novelId)
        }
        startActivity(intent)
    }

    override fun onClickIllust(illustId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                .getOrNull() ?: return@launch
            val gson = Shaft.sGson
            val bean = gson.fromJson(gson.toJson(illust), IllustsBean::class.java)
            val uuid = UUID.randomUUID().toString()
            val pageData = PageData(uuid, null, listOf(bean))
            Container.get().addPageToMap(pageData)
            val intent = Intent(requireContext(), VActivity::class.java).apply {
                putExtra(Params.POSITION, 0)
                putExtra(Params.PAGE_UUID, uuid)
            }
            startActivity(intent)
        }
    }

    companion object {
        const val ARG_USER_ID = "user_id"

        fun newInstance(userId: Long): UncategorizedNovelsFragment =
            UncategorizedNovelsFragment().apply {
                arguments = Bundle().apply { putLong(ARG_USER_ID, userId) }
            }
    }
}
