package ceui.pixiv.ui.detail

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.SheetTagEditBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.loxia.WorkEditableTag
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenHeight
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * issue #1023「编辑标签」MD3-Expressive bottom sheet —— pixiv 社区标签的就地增删。
 *
 * 本类只画 [TagEditViewModel.state],把点击转成 VM 的方法调用;网络与写池在
 * [PixivTagEditOperate],流程与状态在 [TagEditViewModel]。
 *
 * 视觉与 [ceui.pixiv.ui.muted.MuteTagSheet] / [ceui.pixiv.ui.bookmark.SelectTagBottomSheet]
 * 同源:拖拽把手 + headline/supporting 双行标题 + 滚动内容区 + 底部动作条,强调色一律由
 * [V3Palette] 在运行时 tint(root 挂的 Material3 主题会把 colorPrimary 顶成 MD3 基线紫)。
 *
 * ## 为什么是一张 sheet,不是五个弹窗
 * 最初那版是 QMUI:一个 loading tip、一个编辑弹窗、一个删除确认、一个「不可编辑」提示、
 * 一个「去网页登录」提示,五个浮层轮流盖住用户正在看的东西。这些说的都是同一件事的不同阶段,
 * 收进同一张 sheet 之后:
 * - 加载 / 失败 / 不可编辑 / 需登录 → 内容区那一屏([TagEditViewModel.Phase.Blocked]),带一颗对应的按钮;
 * - 提交中 → 顶部一条 indeterminate 进度线,不遮挡内容,输入区就地禁用;
 * - 删除确认 → 底部动作条就地换成确认条,上下文一直在眼前。
 *
 * ## 标签胶囊的两态
 * | 状态 | 表现 |
 * |---|---|
 * | 可删([WorkEditableTag.deletable],通常是自己加的) | 强调色描边胶囊 + 末尾 × |
 * | 不可删(作者指定 / 别人加的) | 压暗的中性胶囊,无 ×,点了给一句解释而不是静默无反应 |
 *
 * ## 变更怎么通知宿主
 * 走 Fragment Result([REQUEST_TAGS_CHANGED]):DialogFragment 会跨横屏重建,拿 lambda 当
 * 回调必然在重建后失效。V3 详情页据此重绑标签区块;V2 靠 [PixivTagEditOperate.applyToPool]
 * 写池后自己的 ObjectPool observer 自然重画,不必监听。
 */
class TagEditSheet : BottomSheetDialogFragment() {

    // edgeToEdge：让 window 画到导航栏底下，root 的圆角背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetTagEditBinding? = null
    private val binding get() = _binding!!

    private val palette by lazy { V3Palette.from(requireContext()) }

    private val viewModel by viewModels<TagEditViewModel> {
        // 零捕获:只把 id 读进局部值交给 VM,不钉 Fragment。
        val id = requireArguments().getLong(KEY_ILLUST_ID)
        viewModelFactory { initializer { TagEditViewModel(id) } }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetTagEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyAccent()

        binding.btnAdd.setOnClickListener { submitInput() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitInput()
                true
            } else {
                false
            }
        }
        binding.btnConfirmCancel.setOnClickListener { viewModel.cancelDelete() }
        binding.btnConfirmDelete.setOnClickListener { viewModel.confirmDelete() }
        binding.stateAction.setOnClickListener { viewModel.onBlockedAction() }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { handle(it) }
            }
        }
    }

    private fun submitInput() {
        viewModel.addTag(binding.input.text?.toString().orEmpty())
    }

    /**
     * 强调色刷到主操作和输入框上:添加 / 删除 = filled 实心,取消 = text,
     * 输入框描边与 hint 跟随主题色。用代码 tint 而非主题属性 —— 见布局文件顶部注释。
     */
    private fun applyAccent() {
        val b = binding
        b.btnAdd.backgroundTintList = ColorStateList.valueOf(palette.primary)
        b.btnAdd.setTextColor(Color.WHITE)
        b.btnConfirmDelete.backgroundTintList = ColorStateList.valueOf(palette.primary)
        b.btnConfirmDelete.setTextColor(Color.WHITE)
        b.btnConfirmCancel.setTextColor(palette.textAccent)
        b.stateAction.backgroundTintList = ColorStateList.valueOf(palette.primary)
        b.stateAction.setTextColor(Color.WHITE)
        b.progressInline.setIndicatorColor(palette.primary)
        b.stateProgress.setIndicatorColor(palette.primary)
        b.inputLayout.boxStrokeColor = palette.textAccent
        b.inputLayout.hintTextColor = ColorStateList.valueOf(palette.textAccent)
        b.input.setTextColor(requireContext().getColor(R.color.v3_text_1))
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    private fun render(state: TagEditViewModel.UiState) {
        val b = _binding ?: return
        val isContent = state.phase == TagEditViewModel.Phase.Content

        b.scrollTags.isVisible = isContent
        b.bottomBar.isVisible = isContent
        b.dividerBottom.isVisible = isContent
        b.stateView.isVisible = !isContent

        if (!isContent) {
            b.stateProgress.isVisible = state.phase == TagEditViewModel.Phase.Loading
            b.stateMessage.text = state.message.orEmpty()
            b.stateAction.isVisible = state.blockedAction != null
            b.stateAction.text = when (state.blockedAction) {
                TagEditViewModel.BlockedAction.Retry -> getString(R.string.retry)
                TagEditViewModel.BlockedAction.WebLogin ->
                    getString(R.string.street_web_login_confirm)
                null -> ""
            }
            return
        }

        // 提交中:顶部进度线 + 输入区禁用,内容不被遮挡。
        b.progressInline.isVisible = state.busy
        b.btnAdd.isEnabled = !state.busy
        b.input.isEnabled = !state.busy
        b.inputLayout.isEnabled = !state.busy

        b.inputBar.isVisible = state.pendingDelete == null
        b.confirmBar.isVisible = state.pendingDelete != null
        state.pendingDelete?.let {
            b.confirmText.text = getString(R.string.work_tag_edit_delete_confirm, it)
        }

        renderChips(state.tags)
    }

    private fun handle(effect: TagEditViewModel.Effect) {
        when (effect) {
            is TagEditViewModel.Effect.Toast -> Common.showToast(effect.msg)
            TagEditViewModel.Effect.ClearInput -> _binding?.input?.text = null
            TagEditViewModel.Effect.TagsChanged ->
                parentFragmentManager.setFragmentResult(REQUEST_TAGS_CHANGED, bundleOf())

            TagEditViewModel.Effect.GoWebLogin -> {
                startActivity(
                    Intent(requireContext(), TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_HOME.key)
                        putExtra(Params.AUTO_WEB_LOGIN, true)
                    }
                )
                dismissAllowingStateLoss()
            }
        }
    }

    // ── 胶囊 ────────────────────────────────────────────────────────────

    /** 全量重建。数量最多 10 个(pixiv 每个作品的标签上限),不值得做增量。 */
    private fun renderChips(tags: List<WorkEditableTag>) {
        val b = _binding ?: return
        b.tagFlow.removeAllViews()
        b.emptyHint.isVisible = tags.isEmpty()
        tags.forEach { tag ->
            if (tag.tag.isNullOrBlank()) return@forEach
            b.tagFlow.addView(createChip(tag))
        }
    }

    private fun createChip(tag: WorkEditableTag): TextView {
        val name = tag.tag.orEmpty()
        val deletable = tag.deletable
        val d = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            textSize = 13.5F
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT
            includeFontPadding = false
            minHeight = 38.ppppx
            compoundDrawablePadding = 6.ppppx
            isClickable = true
            isFocusable = true
            text = chipLabel(tag)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999F * d
                setColor(if (deletable) palette.alpha20 else palette.alpha08)
                setStroke(
                    ((if (deletable) 1.5F else 1F) * d).roundToInt().coerceAtLeast(1),
                    if (deletable) palette.alpha50 else palette.alpha15,
                )
            }
            setTextColor(
                if (deletable) palette.textAccent
                else requireContext().getColor(R.color.v3_text_2)
            )
            // × 只挂在真能删的那些上:一个点了没反应的 × 比没有 × 更糟。
            setCompoundDrawablesRelative(null, null, closeIcon(deletable), null)
            val vPad = (8 * d).roundToInt()
            setPaddingRelative(
                (15 * d).roundToInt(), vPad, ((if (deletable) 12 else 15) * d).roundToInt(), vPad,
            )
            layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = 8.ppppx
                bottomMargin = 8.ppppx
                flexShrink = 0F
            }
            setOnClickListener {
                if (deletable) viewModel.requestDelete(name) else viewModel.rejectDelete()
            }
        }
    }

    private fun closeIcon(deletable: Boolean) =
        if (!deletable) {
            null
        } else {
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_close_black_24dp)
                ?.mutate()
                ?.apply {
                    setBounds(0, 0, 15.ppppx, 15.ppppx)
                    setTint(palette.textAccent)
                }
        }

    /** 胶囊文案:`原文` +（有译名时）淡色小一号的译名。与屏蔽 sheet 同一排版。 */
    private fun chipLabel(tag: WorkEditableTag): CharSequence {
        val name = tag.tag.orEmpty()
        val builder = SpannableStringBuilder(name)
        val translated = tag.translatedName
        if (!translated.isNullOrEmpty() && translated != name) {
            val start = builder.length
            builder.append("  ").append(translated)
            builder.setSpan(
                ForegroundColorSpan(palette.textSeries),
                start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
                RelativeSizeSpan(0.92F),
                start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.apply {
            skipCollapsed = true
            maxHeight = (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // sheet 容器透明 + 内容背景铺进底部 safe area：圆角与配色全由 root 的
        // bg_m3e_sheet_top 负责，否则主题默认 sheet 底会盖在圆角外、暗色模式露白。
        makeSheetTransparentAndFillNavBar()
    }

    companion object {
        const val TAG = "TagEditSheet"

        /** 宿主监听这个 key 即可知道「标签变了,该重绑了」。 */
        const val REQUEST_TAGS_CHANGED = "tag_edit_changed"

        private const val KEY_ILLUST_ID = "tag_edit_illust_id"

        /** 标签最多 10 个,占不满整屏;留一线底图暗示「这是一张浮层」。 */
        private const val MAX_HEIGHT_FRACTION = 0.75F

        /** [TemplateActivity] 的路由 key，不是 UI 文案。 */

        /**
         * 唯一入口。重复 show 由 TAG 挡掉(标签行末尾那格容易连点)。
         *
         * [fm] 要和宿主监听 [REQUEST_TAGS_CHANGED] 用的是同一个 FragmentManager ——
         * sheet 发结果走的是自己的 parentFragmentManager,也就是这里传进来的这个。
         */
        @JvmStatic
        fun show(fm: FragmentManager, illustId: Long) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            TagEditSheet().apply {
                arguments = bundleOf(KEY_ILLUST_ID to illustId)
            }.show(fm, TAG)
        }
    }
}
