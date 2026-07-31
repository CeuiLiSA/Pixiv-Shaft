package ceui.pixiv.plaza.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.CellPlazaAddIllustBinding
import ceui.lisa.databinding.CellPlazaAttachedIllustBinding
import ceui.lisa.databinding.FragmentPlazaComposeBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.session.SessionManager
import com.blankj.utilcode.util.BarUtils
import com.bumptech.glide.Glide
import com.hjq.toast.Toaster
import com.qmuiteam.qmui.widget.dialog.QMUIDialog

/**
 * 发帖编辑器(ProjZ Post Compose 风格)。
 *
 * 顶 bar 自绘 ✕ + Submit 胶囊,不再走 Toolbar/menu。空文本时 Submit 自动 disable,
 * 提交进行中所有控件一起 disable。
 *
 * 已附 illust 显示在底部 108dp 横滑列,列尾常驻虚线「+」槽(达 9 张时 hide)。
 * MVP 添加 illust 走输入 ID 弹窗,server 校验存在性。
 */
class PlazaComposeFragment : Fragment(R.layout.fragment_plaza_compose) {

    private val binding by viewBinding(FragmentPlazaComposeBinding::bind)
    private val viewModel by viewModels { PlazaComposeViewModel() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 从插画详情「分享至广场」入口进来时,带 ARG_PREFILL_ILLUST_ID 自动附上这张
        // illust。只在首次创建(savedInstanceState=null)时附,旋屏重建别重复 attach
        // (VM 已经持有 attachedIllusts)。
        if (savedInstanceState == null) {
            val prefilled = arguments?.getLong(ARG_PREFILL_ILLUST_ID, 0L) ?: 0L
            if (prefilled > 0L) viewModel.attachIllust(prefilled)
        }

        // top bar 走 brand 色 + 跟 setupToolbar 同套(Shaft.getThemeColor 是
        // AppTheme.IndexX 的 colorPrimary)。XML 里的 ?attr/colorPrimary 是 Material3
        // baseline 的 fallback,brand 色才是用户切主题选过的。
        binding.topBar.setBackgroundColor(Color.parseColor(Shaft.getThemeColor()))

        // BaseActivity 走 EdgeToEdge,顶 bar 自己接 status bar inset 作 top padding —
        // 跟 setupToolbar 走的是同一招(BarUtils 兜底用在拿不到 dispatched inset 时)。
        binding.topBar.updatePadding(top = BarUtils.getStatusBarHeight())
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            if (top > 0) v.updatePadding(top = top)
            insets
        }

        binding.btnClose.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.btnSubmit.setOnClickListener { trySubmit() }
        updateSubmitEnabled(textNonEmpty = false, sending = false)

        // 字数计数(按 UTF-16 units 估算,提交时按 code point 严格校)
        binding.textCounter.text = getString(R.string.plaza_text_count, 0)
        binding.textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val codePoints = s?.toString()?.codePointCount(0, s.length) ?: 0
                binding.textCounter.text = getString(R.string.plaza_text_count, codePoints)
                updateSubmitEnabled(
                    textNonEmpty = !s.isNullOrBlank(),
                    sending = viewModel.state.value.isSending,
                )
            }
        })

        // 已附 illust 横滑 + 列尾常驻「+」槽
        val attachedAdapter = AttachedIllustAdapter(
            onRemove = viewModel::removeIllust,
            onAdd = ::showAddIllustDialog,
        )
        binding.attachedList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.attachedList.adapter = attachedAdapter

        launchSuspend {
            viewModel.state.collect { s ->
                attachedAdapter.submit(
                    newItems = s.attachedIllusts,
                    thumbUrls = s.thumbUrls,
                    addEnabled = !s.isSending,
                )
                binding.attachedLabel.text =
                    getString(R.string.plaza_attached_count, s.attachedIllusts.size)
                binding.textInput.isEnabled = !s.isSending
                updateSubmitEnabled(
                    textNonEmpty = !binding.textInput.text.isNullOrBlank(),
                    sending = s.isSending,
                )
            }
        }
        launchSuspend {
            viewModel.events.collect { ev ->
                when (ev) {
                    is PlazaComposeViewModel.Event.Toast -> Toaster.showShort(ev.message)
                    PlazaComposeViewModel.Event.Sent -> {
                        // Plaza 端 SharedFlow 已经 prepend 好新帖,回到 plaza 立即可见。
                        requireActivity().finish()
                    }
                }
            }
        }
    }

    private fun updateSubmitEnabled(textNonEmpty: Boolean, sending: Boolean) {
        val enabled = textNonEmpty && !sending
        binding.btnSubmit.isEnabled = enabled
        // disabled / sending 时整体 dim,跟 Figma 的 40% 不透明 Submit 一致
        binding.btnSubmit.alpha = if (enabled) 1f else 0.4f
    }

    private fun trySubmit() {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) {
            Toaster.showShort(R.string.plaza_login_required)
            return
        }
        viewModel.submit(requireContext(), binding.textInput.text?.toString() ?: "", uid)
    }

    private fun showAddIllustDialog() {
        val builder = QMUIDialog.EditTextDialogBuilder(requireContext())
        builder.setTitle(R.string.plaza_attach_illust_by_id)
            .setPlaceholder(getString(R.string.plaza_attach_illust_id_hint))
            .setInputType(InputType.TYPE_CLASS_NUMBER)
            .addAction(R.string.plaza_delete_cancel) { d, _ -> d.dismiss() }
            .addAction(android.R.string.ok) { d, _ ->
                val id = builder.editText.text?.toString()?.trim()?.toLongOrNull()
                if (id != null && id > 0L) viewModel.attachIllust(id)
                d.dismiss()
            }
            .show()
    }

    companion object {
        /** TemplateActivity 走 EXTRA_FRAGMENT="发帖" 时附带这条:打开编辑器并预附 illust。 */
        const val ARG_PREFILL_ILLUST_ID = "plaza_compose_prefill_illust_id"
    }
}

/**
 * 双视图类型 adapter:已附 illust + 列尾「+」槽。
 * 上限 9 张时列尾「+」隐藏(getItemCount 不再 +1)。
 */
private class AttachedIllustAdapter(
    private val onRemove: (Long) -> Unit,
    private val onAdd: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Long>()
    private var thumbUrls: Map<Long, String> = emptyMap()
    private var addEnabled = true

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<Long>, thumbUrls: Map<Long, String>, addEnabled: Boolean) {
        items.clear()
        items.addAll(newItems)
        this.thumbUrls = thumbUrls
        this.addEnabled = addEnabled
        notifyDataSetChanged()
    }

    private fun hasAddTile(): Boolean = items.size < MAX_ITEMS

    override fun getItemCount(): Int = items.size + if (hasAddTile()) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (hasAddTile() && position == items.size) VIEW_TYPE_ADD else VIEW_TYPE_ATTACHED

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ADD) {
            AddVH(CellPlazaAddIllustBinding.inflate(inflater, parent, false))
        } else {
            AttachedVH(CellPlazaAttachedIllustBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AttachedVH -> {
                val id = items[position]
                holder.bind(id, thumbUrls[id], onRemove)
            }
            is AddVH -> holder.bind(addEnabled, onAdd)
        }
    }

    class AttachedVH(val binding: CellPlazaAttachedIllustBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(id: Long, thumbUrl: String?, onRemove: (Long) -> Unit) {
            binding.btnRemove.setOnClickListener { onRemove(id) }
            if (thumbUrl.isNullOrEmpty()) {
                // meta 还没回来或 fetch 失败 — 用 ID 占位顶一下,thumb 来了 rebind 自然换图
                binding.thumb.setImageDrawable(null)
                binding.placeholderId.isVisible = true
                binding.placeholderId.text = id.toString()
            } else {
                binding.placeholderId.isVisible = false
                Glide.with(binding.thumb.context)
                    .load(GlideUrlChild(thumbUrl))
                    .placeholder(android.R.color.transparent)
                    .into(binding.thumb)
            }
        }
    }

    class AddVH(val binding: CellPlazaAddIllustBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(enabled: Boolean, onAdd: () -> Unit) {
            binding.root.isEnabled = enabled
            binding.root.alpha = if (enabled) 1f else 0.4f
            binding.root.setOnClickListener { if (enabled) onAdd() }
        }
    }

    companion object {
        private const val VIEW_TYPE_ATTACHED = 1
        private const val VIEW_TYPE_ADD = 2
        private const val MAX_ITEMS = 9
    }
}
