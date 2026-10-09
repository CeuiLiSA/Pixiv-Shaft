package ceui.pixiv.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.SheetAiBlockExemptAuthorsBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenHeight
import ceui.pixiv.witstudio.theme.V3Palette
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.LinkedHashSet
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「豁免的作者列表」管理 sheet：逐个添加，每条可单独「启用 / 未启用」，点 × 删除。
 *
 * 每条 ID 带一个二态开关（点胶囊本体在「启用 / 未启用」之间切）：停用后 ID 仍留在名单里、
 * 只是当前不产生豁免效果，省去「删掉再加回来」。
 *
 * ## 落盘
 * 工作副本在 [entries]，只在 [onCreate] 从设置灌一次（DialogFragment 跨旋转/配置变更存活，
 * 放 onViewCreated 里重灌会把用户没保存的改动冲掉）。点「保存」才写回设置，拆成两个集合：
 * `Shaft.sSettings.aiBlockExemptAuthorIds`（全部 ID，含停用的）与
 * `Shaft.sSettings.aiBlockExemptDisabledIds`（被停用的子集）—— 旧设置 / WebDAV 老备份没有后者，
 * 反序列化为空集，等于全部启用，无需迁移。
 *
 * ## 为什么用 RecyclerView
 * 名单可能很长，且每条胶囊带图标 + 删除键。若沿用「整表 removeAllViews + 重建」，点开那一帧要在
 * 主线程 inflate 全部胶囊（patch-10-6-2 同类卡顿）。这里用 [FlexboxLayoutManager] 保留原来的换行
 * 胶囊布局，但只建可见项；切换走 `notifyItemChanged`、增删走 `notifyItemInserted/Removed`，
 * 任何操作都不再重建整表。
 */
class AiBlockExemptAuthorsSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetAiBlockExemptAuthorsBinding? = null
    private val binding get() = _binding!!

    private val palette by lazy { V3Palette.from(requireContext()) }

    /** 工作副本（保持添加顺序）。只在 [onCreate] 灌一次，见那里的注释。 */
    private val entries = mutableListOf<ExemptEntry>()

    private var adapter: ExemptAdapter? = null

    /**
     * 有「把新加的那条滚到底」还没完成时置位。
     *
     * 滚动目标是按下令那一刻的可视高度算的，而键盘抬高 / 收起会改这个高度 —— 按旧基准滚就
     * 到不了新的底。所以布局高度一变就按新基准补滚一次，滚到真的到底（或用户自己拖动）才撤掉。
     */
    private var pendingScrollToEnd = false

    /** 高度变化后的「补滚」任务：键盘动画期间高度每帧都在变，用防抖合并成一次，见 onViewCreated。 */
    private val reScrollAfterResize = Runnable {
        _binding?.let { scrollToEndIfPending(it.idList) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只在建实例时从设置灌一次：DialogFragment 跨旋转/配置变更存活，若放在 onViewCreated 里
        // 重灌，转屏一次就把用户刚删掉、还没保存的 ID 又加回来。clear() 让同一实例上的重入幂等。
        entries.clear()
        val disabled = Shaft.sSettings.aiBlockExemptDisabledIds
        Shaft.sSettings.aiBlockExemptAuthorIds.forEach { id ->
            entries.add(ExemptEntry(id, enabled = !disabled.contains(id)))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetAiBlockExemptAuthorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.idList.removeCallbacks(reScrollAfterResize)
        binding.idList.adapter = null
        adapter = null
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyAccent()
        binding.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnAdd.setOnClickListener { addCurrentInput() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCurrentInput()
                true
            } else {
                false
            }
        }
        binding.idList.layoutManager = FlexboxLayoutManager(requireContext()).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            alignItems = AlignItems.FLEX_START
        }
        binding.idList.adapter = ExemptAdapter().also { adapter = it }
        // ItemAnimator 保持默认，**不要** override 成返回 false：
        // RecyclerView.animateDisappearance 会先把视图挂成脱离布局的 animating view（不随滚动），
        // 动画器返回 false 时它不会 postAnimationRunner()，那个视图就永远留在屏上 ——
        // 表现为「删除后残留一条无视滚动的胶囊」。animateAdd 返回 false 同理会把 ViewHolder
        // 永久标成不可回收。「插入 + 滚动」那点伪影由 addCurrentInput 里的 doOnNextLayout 拆帧解决。
        renderEmpty()
        // 高度一变（键盘抬高 / 收起、转屏）就按新基准补滚一次，见 [pendingScrollToEnd]。
        // 键盘动画期间高度每帧都在变，用 60ms 防抖合并成一次 —— 每帧重启滚动正是「掉帧」的来源。
        binding.idList.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && pendingScrollToEnd) {
                v.removeCallbacks(reScrollAfterResize)
                v.postDelayed(reScrollAfterResize, RESIZE_RESCROLL_DELAY_MS)
            }
        }
        binding.idList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    // 用户自己接管了手势，或已经滚到底：撤掉待办
                    RecyclerView.SCROLL_STATE_DRAGGING -> pendingScrollToEnd = false
                    RecyclerView.SCROLL_STATE_IDLE ->
                        if (!rv.canScrollVertically(1)) pendingScrollToEnd = false
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.apply {
            skipCollapsed = true
            // 拖拽保持启用（把手 / 标题 / 底部动作条都能拖，可下滑关闭）。列表滚动的余量不再
            // 驱动 sheet —— 由布局里那层 WitNestedScrollBlockingLayout 在中间截停，见它的注释。
            maxHeight = collapsedMaxHeight()
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        makeSheetTransparentAndFillNavBar()
        // 覆盖公共 helper 挂在 root 上的 inset listener，多管一件事：随键盘高度放宽 sheet 高度上限。
        //
        // 为什么必须放宽：sheet 底边钉在窗口底，内容按 IME 上抬后，留给内容的可用高度 =
        // maxHeight - ime。默认 0.75 屏高，键盘占掉 45% 时只剩约 30% 屏高，扣掉标题区与底部
        // 两行动作条（输入框 + 取消/保存）后列表只剩十几 dp —— 就是「列表被挤没」。
        // 让 maxHeight 跟着 ime 一起长（0.75 屏高 + ime，封顶到系统栏下方），内容可用高度便
        // 始终维持在 0.75 屏高：sheet 顶边随键盘上移，列表不被压缩；收起键盘后自然回到 0.75。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            dialog.behavior.maxHeight = min(screenHeight - bars.top, collapsedMaxHeight() + ime)
            v.updatePadding(bottom = bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /** 无键盘时的 sheet 高度上限（屏高的 [MAX_HEIGHT_FRACTION]）。 */
    private fun collapsedMaxHeight(): Int = (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()

    private fun addCurrentInput() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        // 作者 ID 必须是正整数：IllustNovelFilter.isAiExemptAuthor 对 <=0 一律不认，
        // 这里不拦的话「0」会被加进列表、保存成功却永远不生效。
        val id = text.toLongOrNull()
        if (id == null || id <= 0L) {
            Common.showToast(getString(R.string.ai_block_exempt_invalid))
            return
        }
        if (entries.any { it.id == id }) {
            Common.showToast(getString(R.string.ai_block_exempt_duplicate))
            return
        }
        binding.input.text?.clear()
        entries.add(ExemptEntry(id, enabled = true))
        adapter?.notifyItemInserted(entries.size - 1)
        renderEmpty()
        pendingScrollToEnd = true
        val list = binding.idList
        // 关键：滚动必须等「插入这一版布局」跑完再开始。
        // 只用 post 不够 —— post 排在消息队列里，可能赶在插入那次布局之前跑，于是插入与滚动
        // 落进同一版布局：动画器会把滚动位移当成 item 的 move（顶部胶囊先「掉一下」）、把滚出
        // 视野的项当成 remove（露着的胶囊淡出又瞬间出现）。doOnNextLayout 保证排在插入布局之后，
        // 那一版没有滚动、随后滚动那一版又没有数据变化 —— 动画器两次都不会误播。
        list.doOnNextLayout {
            list.post { scrollToEndIfPending(list) }
        }
    }

    private fun toggleAt(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        entry.enabled = !entry.enabled
        adapter?.notifyItemChanged(position)
    }

    private fun removeAt(position: Int) {
        if (position !in entries.indices) return
        entries.removeAt(position)
        adapter?.notifyItemRemoved(position)
        renderEmpty()
    }

    private fun renderEmpty() {
        binding.emptyHint.isVisible = entries.isEmpty()
    }

    /** 把新加的那条平滑滚到底；待办已撤（已完成 / 用户接管）就什么都不做。 */
    private fun scrollToEndIfPending(list: RecyclerView) {
        if (!pendingScrollToEnd) return
        val last = entries.lastIndex
        if (last < 0) return
        val scroller = object : LinearSmoothScroller(list.context) {
            // 只放慢速度。**不要**改 getVerticalSnapPreference：SNAP_TO_END 会把目标硬对到
            // 内容区底部，配合列表自身的 paddingBottom / clipToPadding=false 会滚过头
            // （尾部留白、露着的胶囊被推出视野又弹回）。默认的 SNAP_TO_ANY 只保证目标进入
            // 视野，不会过冲。
            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                80f / displayMetrics.densityDpi
        }
        scroller.targetPosition = last
        list.layoutManager?.startSmoothScroll(scroller)
    }

    private fun applyAccent() {
        binding.inputLayout.boxStrokeColor = palette.textAccent
        binding.inputLayout.hintTextColor = ColorStateList.valueOf(palette.textAccent)
        binding.input.setTextColor(requireContext().getColor(R.color.v3_text_1))
        binding.btnAdd.backgroundTintList = ColorStateList.valueOf(palette.primary)
        binding.btnAdd.setTextColor(Color.WHITE)
        binding.btnSave.backgroundTintList = ColorStateList.valueOf(palette.primary)
        binding.btnSave.setTextColor(Color.WHITE)
        binding.btnCancel.setTextColor(palette.textAccent)
    }

    private fun save() {
        val ids = LinkedHashSet<Long>()
        val disabled = LinkedHashSet<Long>()
        entries.forEach { entry ->
            ids.add(entry.id)
            if (!entry.enabled) {
                disabled.add(entry.id)
            }
        }
        Shaft.sSettings.aiBlockExemptAuthorIds = ids
        Shaft.sSettings.aiBlockExemptDisabledIds = disabled
        Local.setSettings(Shaft.sSettings)
        Common.showToast(getString(R.string.please_restart_app), 2)
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_CHANGED to true))
        dismissAllowingStateLoss()
    }

    /** 一条豁免记录：ID + 当前是否启用（停用 = 留在名单里但不生效）。 */
    private data class ExemptEntry(val id: Long, var enabled: Boolean)

    private inner class ExemptAdapter : RecyclerView.Adapter<ExemptAdapter.VH>() {

        inner class VH(
            val chip: LinearLayout,
            val label: TextView,
            val delete: AppCompatImageView,
        ) : RecyclerView.ViewHolder(chip)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }
                layoutParams = FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = 8.ppppx
                    bottomMargin = 8.ppppx
                    flexShrink = 0F
                }
            }
            val label = TextView(ctx).apply {
                textSize = 13.5F
                includeFontPadding = false
                compoundDrawablePadding = 6.ppppx
            }
            val delete = AppCompatImageView(ctx).apply {
                setImageResource(R.drawable.ic_delete_black_24dp)
                contentDescription = ctx.getString(R.string.action_delete)
            }
            chip.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            chip.addView(
                delete,
                LinearLayout.LayoutParams(18.ppppx, 18.ppppx).apply { marginStart = 8.ppppx },
            )
            return VH(chip, label, delete)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            holder.label.text = entry.id.toString()
            renderChip(holder.chip, holder.label, holder.delete, entry.enabled)
            holder.chip.contentDescription = entry.id.toString() + " " + holder.chip.context.getString(
                if (entry.enabled) R.string.ai_block_exempt_state_on else R.string.ai_block_exempt_state_off,
            )
            // 整条胶囊是切换热区；删除键自己消费点击，不会误触切换。
            holder.chip.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) toggleAt(pos)
            }
            holder.delete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) removeAt(pos)
            }
        }

        override fun getItemCount(): Int = entries.size
    }

    /**
     * 把一条胶囊按当前状态画出来（复用同一实例，必须在绑定里把所有状态重设一遍）：
     * - 启用：主题浅底 + 实线描边 + 勾，字色 [V3Palette.textAccent]；
     * - 未启用：「挂着但没工作」——淡底 + 虚线描边、不挂图标，字色降一档。
     *
     * 未启用刻意不挂 eye-off：那在「豁免」语境里会被读成「隐藏 / 不看」，与「临时停用」歧义。
     * 虚线描边这一层沿用 [ceui.pixiv.ui.muted.MuteTagSheet] 的「未生效」语言。
     */
    private fun renderChip(
        chip: LinearLayout,
        label: TextView,
        delete: AppCompatImageView,
        enabled: Boolean,
    ) {
        val ctx = chip.context
        val d = chip.resources.displayMetrics.density
        val bg = chip.background as? GradientDrawable ?: return
        bg.cornerRadius = 999F * d
        val stroke = (1.5F * d).roundToInt().coerceAtLeast(1)
        if (enabled) {
            bg.setColor(palette.alpha20)
            bg.setStroke(stroke, palette.alpha50)
        } else {
            bg.setColor(palette.alpha08)
            bg.setStroke(stroke, palette.alpha15, 5F * d, 4F * d)
        }
        chip.setPadding(
            (15 * d).roundToInt(),
            (6 * d).roundToInt(),
            (8 * d).roundToInt(),
            (6 * d).roundToInt(),
        )
        val tint = if (enabled) palette.textAccent else ctx.getColor(R.color.v3_text_2)
        label.setTextColor(tint)
        // 只有「启用」挂勾。未启用不挂图标：eye-off 在「豁免」语境里会被读成「隐藏 / 不看」，
        // 与「临时停用」歧义，靠淡底 + 虚线描边 + 降档字色表达「挂着但没工作」就够了。
        val icon = if (enabled) {
            AppCompatResources.getDrawable(ctx, R.drawable.ic_check_24dp)?.mutate()?.apply {
                setTint(tint)
                setBounds(0, 0, 14.ppppx, 14.ppppx)
            }
        } else {
            // 透明占位：未启用态不挂勾，但保留同尺寸空白，切换时胶囊宽度不变、换行布局不回流。
            ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, 14.ppppx, 14.ppppx) }
        }
        label.setCompoundDrawablesRelative(icon, null, null, null)
        delete.imageTintList = ColorStateList.valueOf(tint)
    }

    companion object {
        const val REQUEST_KEY = "ai_block_exempt_authors_changed"
        const val RESULT_CHANGED = "changed"
        private const val MAX_HEIGHT_FRACTION = 0.75F

        /** 布局高度变化后延迟多久补滚（合并键盘动画期间的连续高度变化）。 */
        private const val RESIZE_RESCROLL_DELAY_MS = 60L
    }
}
