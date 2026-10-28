package ceui.pixiv.ui.detail

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.upscale.IllustAiStore
import ceui.pixiv.ui.upscale.PageAiState
import ceui.pixiv.utils.ppppx
import ceui.lisa.view.SeamlessCircularProgressIndicator
import com.blankj.utilcode.util.BarUtils

/**
 * 详情页大图基类适配器：负责大图单页的展示，以及局部挂载的 AI 工作进度遮罩与 AI 结果胶囊。
 */
open class ArtworkDetailIllustAdapter(
    activity: FragmentActivity,
    fragment: Fragment,
    protected val illust: Illust,
    maxHeight: Int,
    isForceOriginal: Boolean,
) : IllustAdapter(activity, fragment, illust, maxHeight, isForceOriginal) {

    var onUpscalePillClick: ((position: Int, state: PageAiState) -> Unit)? = null
    var onRembgPillClick: ((position: Int, state: PageAiState) -> Unit)? = null

    internal open fun bindOverlayOnly(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
    ): Boolean {
        bindExpandOverlay(holder, position, fadeIn = false)
        bindWorkingOverlay(holder, position)
        return true
    }

    internal open fun refreshExpandOverlay(holder: ViewHolder<RecyIllustDetailBinding>, position: Int) {
        bindExpandOverlay(holder, position, fadeIn = false)
        bindWorkingOverlay(holder, position)
    }

    fun refreshOverlayAndWorking(holder: ViewHolder<RecyIllustDetailBinding>, position: Int) {
        bindExpandOverlay(holder, position, fadeIn = false)
        bindWorkingOverlay(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder<RecyIllustDetailBinding>, position: Int) {
        super.onBindViewHolder(holder, position)
        bindExpandOverlay(holder, position, fadeIn = false)
        bindWorkingOverlay(holder, position)
    }

    override fun onBindViewHolder(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        when {
            payloads.contains(CollapsibleIllustAdapter.PAYLOAD_OVERLAY_FADE_IN) -> {
                bindExpandOverlay(holder, position, fadeIn = true)
                bindWorkingOverlay(holder, position)
            }
            payloads.contains(CollapsibleIllustAdapter.PAYLOAD_OVERLAY_ONLY) || payloads.contains(CollapsibleIllustAdapter.PAYLOAD_AI_UPDATE) -> {
                bindExpandOverlay(holder, position, fadeIn = false)
                bindWorkingOverlay(holder, position)
            }
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    protected fun bindExpandOverlay(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        fadeIn: Boolean,
    ) {
        val collapsible = this as? CollapsibleIllustAdapter
        val showExpand = position == 0 && collapsible?.isCollapsed == true && collapsible.isCollapsible
        val aiState = IllustAiStore.get(illust.id, position)
        val canShowAiPills = !showExpand
        val hasUpscale = canShowAiPills && aiState?.hasUpscaleResult == true
        val hasRembg = canShowAiPills && aiState?.hasRembgResult == true
        val shouldShowOverlay = showExpand || hasUpscale || hasRembg

        if (!shouldShowOverlay) {
            val views = overlayOf(holder) ?: return
            val overlay = views.overlay
            overlay.animate().cancel()
            overlay.visibility = View.GONE
            overlay.alpha = 1f
            val pill = views.expandPill
            pill.animate().cancel()
            pill.scaleX = 1f
            pill.scaleY = 1f
            pill.setOnClickListener(null)
            pill.setOnTouchListener(null)
            views.comicPill.visibility = View.GONE
            views.comicPill.setOnClickListener(null)
            views.upscalePill.visibility = View.GONE
            views.upscalePill.setOnClickListener(null)
            views.rembgPill.visibility = View.GONE
            views.rembgPill.setOnClickListener(null)
            return
        }

        val views = ensureOverlayInflated(holder) ?: return
        val overlay = views.overlay
        val pill = views.expandPill
        val label = views.expandLabel
        val comicPill = views.comicPill
        val upscalePill = views.upscalePill
        val rembgPill = views.rembgPill

        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        if (fadeIn) {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(CollapsibleIllustAdapter.FADE_MS).start()
        } else {
            overlay.alpha = 1f
        }

        if (showExpand) {
            pill.visibility = View.VISIBLE
            label.text = holder.itemView.context.getString(
                R.string.v3_expand_all_pages_title, collapsible?.hiddenCount ?: 0
            )

            val hasReader = collapsible?.onComicReaderClick != null
            comicPill.visibility = if (hasReader) View.VISIBLE else View.GONE
            if (hasReader) {
                views.comicPillLabel.setText(comicReaderEnterTextRes(illust))
                applyPillTouchFeedback(comicPill)
                comicPill.setOnClickListener { collapsible?.onComicReaderClick?.invoke() }
            }

            applyPillTouchFeedback(pill)
            pill.setOnLongClickListener { v ->
                if (collapsible?.onExpandPillLongClick?.invoke() != true) return@setOnLongClickListener false
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                true
            }
            pill.setOnClickListener {
                val state = IllustAiStore.get(illust.id, position)
                val willHaveAiPills = state?.hasUpscaleResult == true || state?.hasRembgResult == true
                if (willHaveAiPills) {
                    collapsible?.expand(callerFadesOverlay = false)
                    if (state.hasUpscaleResult) {
                        upscalePill.alpha = 0f
                        upscalePill.animate().alpha(1f).setDuration(CollapsibleIllustAdapter.FADE_MS).start()
                    }
                    if (state.hasRembgResult) {
                        rembgPill.alpha = 0f
                        rembgPill.animate().alpha(1f).setDuration(CollapsibleIllustAdapter.FADE_MS).start()
                    }
                } else {
                    collapsible?.expand(callerFadesOverlay = true)
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(CollapsibleIllustAdapter.FADE_MS)
                        .withEndAction {
                            overlay.visibility = View.GONE
                            overlay.alpha = 1f
                        }
                        .start()
                }
            }

            upscalePill.visibility = View.GONE
            upscalePill.setOnClickListener(null)
            rembgPill.visibility = View.GONE
            rembgPill.setOnClickListener(null)
        } else {
            pill.visibility = View.GONE
            pill.setOnClickListener(null)
            comicPill.visibility = View.GONE
            comicPill.setOnClickListener(null)

            upscalePill.visibility = if (hasUpscale) View.VISIBLE else View.GONE
            if (hasUpscale) {
                applyPillTouchFeedback(upscalePill)
                upscalePill.setOnClickListener {
                    aiState?.let { onUpscalePillClick?.invoke(position, it) }
                }
            } else {
                upscalePill.setOnClickListener(null)
            }

            rembgPill.visibility = if (hasRembg) View.VISIBLE else View.GONE
            if (hasRembg) {
                applyPillTouchFeedback(rembgPill)
                rembgPill.setOnClickListener {
                    aiState?.let { onRembgPillClick?.invoke(position, it) }
                }
            } else {
                rembgPill.setOnClickListener(null)
            }

            val eightDp = holder.itemView.context.resources.getDimensionPixelSize(R.dimen.eight_dp)
            if (hasUpscale && hasRembg) {
                (upscalePill.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = 0
                (rembgPill.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = eightDp
            } else if (hasRembg) {
                (rembgPill.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = 0
            } else if (hasUpscale) {
                (upscalePill.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = 0
            }
        }
    }

    private fun ensureOverlayInflated(
        holder: ViewHolder<RecyIllustDetailBinding>,
    ): OverlayViews? {
        overlayOf(holder)?.let { return it }
        val stub = holder.itemView.findViewById<ViewStub>(R.id.expand_overlay_stub) ?: return null
        stub.inflate()
        return overlayOf(holder)
    }

    private class OverlayViews(
        val overlay: FrameLayout,
        val expandPill: View,
        val expandLabel: TextView,
        val comicPill: View,
        val comicPillLabel: TextView,
        val upscalePill: View,
        val upscaleLabel: TextView,
        val rembgPill: View,
        val rembgLabel: TextView,
    )

    private fun overlayOf(holder: ViewHolder<RecyIllustDetailBinding>): OverlayViews? {
        val root = holder.itemView
        (root.getTag(CollapsibleIllustAdapter.TAG_OVERLAY) as? OverlayViews)?.let { return it }
        val overlay = root.findViewById<FrameLayout>(R.id.expand_overlay) ?: return null
        val pill = root.findViewById<View>(R.id.expand_pill) ?: return null
        val label = root.findViewById<TextView>(R.id.expand_label) ?: return null
        val comicPill = root.findViewById<View>(R.id.comic_reader_pill) ?: return null
        val comicPillLabel = root.findViewById<TextView>(R.id.comic_reader_pill_label) ?: return null
        val upscalePill = root.findViewById<View>(R.id.ai_upscale_pill) ?: return null
        val upscaleLabel = root.findViewById<TextView>(R.id.ai_upscale_pill_label) ?: return null
        val rembgPill = root.findViewById<View>(R.id.ai_rembg_pill) ?: return null
        val rembgLabel = root.findViewById<TextView>(R.id.ai_rembg_pill_label) ?: return null
        val views = OverlayViews(overlay, pill, label, comicPill, comicPillLabel, upscalePill, upscaleLabel, rembgPill, rembgLabel)
        root.setTag(CollapsibleIllustAdapter.TAG_OVERLAY, views)
        return views
    }

    // ── AI 任务进行中工作覆盖层 ───────────────────────────────────

    private class WorkingOverlayViews(
        val root: FrameLayout,
        val loadingState: View,
        val doneState: View,
        val progressRing: SeamlessCircularProgressIndicator,
        val progressText: TextView,
        val statusText: TextView,
        val etaText: TextView,
    )

    private fun workingOverlayOf(holder: ViewHolder<RecyIllustDetailBinding>): WorkingOverlayViews? {
        val root = holder.itemView
        (root.getTag(CollapsibleIllustAdapter.TAG_WORKING_OVERLAY) as? WorkingOverlayViews)?.let { return it }
        val overlay = root.findViewById<FrameLayout>(R.id.ai_overlay_root) ?: return null
        val loading = root.findViewById<View>(R.id.ai_loading_state) ?: return null
        val done = root.findViewById<View>(R.id.ai_done_state) ?: return null
        val ring = root.findViewById<SeamlessCircularProgressIndicator>(R.id.ai_progress_ring) ?: return null
        val progText = root.findViewById<TextView>(R.id.ai_progress_text) ?: return null
        val statText = root.findViewById<TextView>(R.id.ai_status_text) ?: return null
        val eta = root.findViewById<TextView>(R.id.ai_eta_text) ?: return null
        val views = WorkingOverlayViews(overlay, loading, done, ring, progText, statText, eta)
        root.setTag(CollapsibleIllustAdapter.TAG_WORKING_OVERLAY, views)
        return views
    }

    private fun ensureWorkingOverlayInflated(holder: ViewHolder<RecyIllustDetailBinding>): WorkingOverlayViews? {
        workingOverlayOf(holder)?.let { return it }
        val stub = holder.itemView.findViewById<ViewStub>(R.id.ai_working_overlay_stub) ?: return null
        stub.inflate()
        return workingOverlayOf(holder)
    }

    fun bindWorkingOverlay(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
    ) {
        val aiState = IllustAiStore.get(illust.id, position)
        if (aiState == null || !aiState.isWorking) {
            val views = workingOverlayOf(holder) ?: return
            if (views.root.visibility == View.VISIBLE) {
                views.root.animate().cancel()
                views.root.animate().alpha(0f).setDuration(200L).withEndAction {
                    views.root.visibility = View.GONE
                    views.root.alpha = 1f
                }.start()
            }
            return
        }

        val views = ensureWorkingOverlayInflated(holder) ?: return
        val isFirstShow = views.root.visibility != View.VISIBLE || views.root.alpha < 1f
        views.root.visibility = View.VISIBLE
        views.loadingState.visibility = View.VISIBLE
        views.doneState.visibility = View.GONE
        if (isFirstShow) {
            views.root.animate().cancel()
            views.root.alpha = 0f
            views.root.animate().alpha(1f).setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
        }

        views.statusText.text = aiState.currentStatusText(holder.itemView.context)
        if (aiState.isIndeterminate) {
            views.progressRing.isIndeterminate = true
            views.progressText.visibility = View.GONE
        } else {
            views.progressRing.isIndeterminate = false
            views.progressText.visibility = View.VISIBLE
            val p = (aiState.currentProgress * 100).toInt()
            views.progressRing.setProgressCompat(p, true)
            views.progressText.text = holder.itemView.context.getString(R.string.string_ai_task_percent, p)
        }
        val etaSec = (aiState.currentEta + 0.5f).toInt()
        views.etaText.text = if (etaSec > 0 && aiState.currentProgress > 0f) holder.itemView.context.getString(R.string.string_ai_task_eta_seconds, etaSec) else ""
    }

    fun updateWorkingOverlayProgress(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        progress: Float,
        eta: Float,
        statusText: String?,
    ) {
        val views = workingOverlayOf(holder) ?: ensureWorkingOverlayInflated(holder) ?: return
        views.root.visibility = View.VISIBLE
        views.loadingState.visibility = View.VISIBLE
        views.doneState.visibility = View.GONE
        if (statusText != null) {
            views.statusText.text = statusText
        }
        if (progress <= 0f) {
            views.progressRing.isIndeterminate = true
            views.progressText.visibility = View.GONE
        } else {
            views.progressRing.isIndeterminate = false
            views.progressText.visibility = View.VISIBLE
            val p = (progress * 100).toInt()
            views.progressRing.setProgressCompat(p, true)
            views.progressText.text = holder.itemView.context.getString(R.string.string_ai_task_percent, p)
        }
        val etaSec = (eta + 0.5f).toInt()
        views.etaText.text = if (etaSec > 0 && progress > 0f) holder.itemView.context.getString(R.string.string_ai_task_eta_seconds, etaSec) else ""
    }

    @SuppressLint("ClickableViewAccessibility")
    protected fun applyPillTouchFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(120)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }
    }

    override fun onViewAttachedToWindow(holder: ViewHolder<RecyIllustDetailBinding>) {
        super.onViewAttachedToWindow(holder)
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
            lp.isFullSpan = true
            holder.itemView.layoutParams = lp
        }
    }
}

/**
 * 非折叠作品（1P / 2P）使用的单页大图适配器。
 */
open class NonCollapsibleIllustAdapter(
    activity: FragmentActivity,
    fragment: Fragment,
    illust: Illust,
    maxHeight: Int,
    isForceOriginal: Boolean,
    var onComicReaderClick: (() -> Unit)? = null,
) : ArtworkDetailIllustAdapter(activity, fragment, illust, maxHeight, isForceOriginal) {
    override fun onBindViewHolder(holder: ViewHolder<RecyIllustDetailBinding>, position: Int) {
        super.onBindViewHolder(holder, position)
        if ("manga" == illust.type && illust.page_count > 1) {
            holder.itemView.setOnClickListener { onComicReaderClick?.invoke() }
        }
    }
}

/**
 * V3-only IllustAdapter that hides all but the first [collapsedCount] pages of a
 * multi-page illust. Call [expand] to reveal the rest, [collapse] to fold back.
 *
 * The "展开剩余 X 张" CTA renders as a bottom scrim + glass pill overlaid on the
 * FIRST page's image itself — no separate adapter row needed.
 *
 * The collapse CTA is intentionally NOT placed on a list item: putting it at the
 * end would force users to scroll through everything they just opened, defeating
 * the purpose of the collapse. The host fragment owns a floating "收起" pill it
 * toggles via [onExpandedChanged].
 */
class CollapsibleIllustAdapter(
    activity: FragmentActivity,
    fragment: Fragment,
    illust: Illust,
    maxHeight: Int,
    isForceOriginal: Boolean,
    private val collapsedCount: Int = DEFAULT_COLLAPSED,
    /**
     * 进页即展开态（issue #1090）。由宿主按**列表实际内容**决定，不直接读设置：数据源一次产出全 P
     * 时列表已经是展开形状，这里只把状态对上，不发通知、也不回调 [onExpandedChanged]
     * （「收起」胶囊由宿主自己点亮）。
     *
     * 与 [expand] 的差异只有「不再重扫下载库」——那一趟是给「先折叠、后下载、再展开」补的；构造时
     * 那趟扫描本就按 page_count 全量走（见 IllustAdapter.scanLocalDownloads），进页即展开已覆盖。
     */
    initiallyExpanded: Boolean = false,
    var onComicReaderClick: (() -> Unit)? = null,
    var onExpandedChanged: ((expanded: Boolean) -> Unit)? = null,
    /**
     * 长按「展开剩余 X 张」→ 多图预览(#1085)。折叠态下这枚 CTA 才是用户眼里「这里还有几张」
     * 的落点,跟右上角的阅读胶囊是同一个诉求的两个入口,手势保持一致(都是长按)。
     * 返回是否真的弹出了预览 —— 没弹就别把这次长按吃掉,单击展开照常。
     */
    var onExpandPillLongClick: (() -> Boolean)? = null,
) : ArtworkDetailIllustAdapter(activity, fragment, illust, maxHeight, isForceOriginal) {

    private var expanded = initiallyExpanded

    /** super 里 [maxHeight] 是 private,这里留一份给折叠封面兜高用(见 [floorCoverHeight])。 */
    private val coverMaxHeight: Int = maxHeight

    val totalPages: Int get() = illust.page_count
    val hiddenCount: Int get() = (totalPages - collapsedCount).coerceAtLeast(0)
    val isCollapsible: Boolean get() = shouldCollapse(totalPages, collapsedCount)
    val isCollapsed: Boolean get() = !expanded && isCollapsible
    val isExpanded: Boolean get() = expanded && isCollapsible

    override fun getItemCount(): Int {
        val total = super.getItemCount()
        if (!isCollapsible) return total
        return if (expanded) total else minOf(total, collapsedCount)
    }

    /**
     * 展开。展开后 p0 的「展开剩余 X 张」覆盖层由这里负责收掉 —— 除非调用方自己跑那趟淡出。
     *
     * @param callerFadesOverlay 调用方是否紧接着自己把 p0 覆盖层淡出（alpha 1→0，收尾 GONE）。
     *   「展开剩余 X 张」胶囊点击传 true：它要的就是那趟 220ms 交叉淡入淡出，adapter 再动这层
     *   scrim 只会把动画 cancel 掉。其余（页面预览跳页等程序化展开）保持默认 false。
     *
     *   为什么不能指望「下一次自然重绑」把覆盖层带走(#1178)：展开时 p0 的条目一个字段都没变
     *   —— 宿主只往后追加 p1..pN-1（见 ArtworkV3Fragment.onPagesExpandedChanged），DiffUtil
     *   判不出变化、不会重绑 p0；而 p0 的 holder 又落在 listView.setItemViewCacheSize(6) 的
     *   缓存窗口里，缓存里的 holder 复用时不回调 onBindViewHolder —— 滑回 p0 也不会重绑。
     */
    fun expand(callerFadesOverlay: Boolean = false) {
        if (expanded) return
        // 展开时再扫一遍下载库：覆盖「未展开时下载、随后展开」——此时第 2 张及之后
        // 才首次绑定，扫到本地文件就直读，不回 pixiv 重新下。
        scanLocalDownloads()
        val prev = itemCount
        expanded = true
        val added = itemCount - prev
        if (added > 0) notifyItemRangeInserted(prev, added)
        if (!callerFadesOverlay) retractExpandOverlay()
        // 覆盖层的收回分两路：调用方自己跑淡出的（展开胶囊点击）传 callerFadesOverlay=true，
        // 其余程序化展开（页面预览跳页）由这里直接收回 —— 别指望「下一次自然重绑」兜底(#1178)。
        onExpandedChanged?.invoke(true)
    }

    /**
     * 立刻把 p0 上还亮着的「展开剩余 X 张」覆盖层收掉（「用阅读器看」胶囊也画在这一层里），不等重绑。
     */
    private fun retractExpandOverlay() {
        val binding = boundBinding(0) ?: return
        bindExpandOverlay(ViewHolder(binding), 0, fadeIn = false)
    }

    fun collapse() {
        if (!expanded) return
        val prev = itemCount
        expanded = false
        val removed = prev - itemCount
        if (removed > 0) notifyItemRangeRemoved(itemCount, removed)
        // Tell pos 0 to fade its expand CTA back IN (alpha 0 → 1), in sync
        // with the host fragment's collapse-pill fade-out.
        notifyItemChanged(0, PAYLOAD_OVERLAY_FADE_IN)
        onExpandedChanged?.invoke(false)
    }

    override fun applyPixelSize(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        w: Int,
        h: Int,
    ) {
        super.applyPixelSize(holder, position, w, h)
        if (position == 0 && isCollapsible && !isPanoramaPage(position)) {
            floorCoverHeight(holder, w, h)
        }
    }

    private fun floorCoverHeight(holder: ViewHolder<RecyIllustDetailBinding>, w: Int, h: Int) {
        val image = holder.baseBind.illust
        if (w <= 0 || h <= 0) return
        val natural = (imageSize.toLong() * h / w).toInt()
        if (natural <= 0 || natural >= coverToolbarClearance()) return
        val target = if (coverMaxHeight > 0) coverMaxHeight else coverToolbarClearance()
        image.setHeightRatio(0f)
        holder.baseBind.illustHd.setHeightRatio(0f)
        val lp = image.layoutParams ?: return
        if (lp.height != target) {
            lp.height = target
            image.layoutParams = lp
        }
    }

    private fun coverToolbarClearance(): Int =
        BarUtils.getStatusBarHeight() + COVER_CLEARANCE_BELOW_STATUS_DP.ppppx

    companion object {
        /** How many pages to show before collapsing. */
        const val DEFAULT_COLLAPSED = 1

        /**
         * 判定「极端窄封面」的顶栏净空阈值(dp,状态栏之外)。功能上只需保证底部胶囊区
         * (≈54dp:40dp 胶囊 + 14dp 边距)落在返回键(状态栏 + 48dp)之下,即 ≈102dp;取 120dp
         * 留余量。之前的 160dp 过于激进:0.5 ratio 左右的「正常偏宽」封面(自然高约屏宽一半)
         * 也被拉到 maxHeight,凭空多出大片黑边(用户反馈:首图应保持自然高度贴顶)。
         */
        private const val COVER_CLEARANCE_BELOW_STATUS_DP = 120

        /** 1P and 2P are always shown in full; 3P and up get collapsed. */
        fun shouldCollapse(pageCount: Int, collapsedCount: Int = DEFAULT_COLLAPSED): Boolean {
            return pageCount > 2
        }

        val PAYLOAD_AI_UPDATE = Any()
        internal val PAYLOAD_OVERLAY_ONLY = Any()
        internal val PAYLOAD_OVERLAY_FADE_IN = Any()
        internal val TAG_OVERLAY = R.id.expand_overlay
        internal val TAG_WORKING_OVERLAY = R.id.ai_working_overlay_stub
        internal const val FADE_MS = 220L
    }
}

/**
 * 进阅读器入口的文案:漫画说「阅读漫画」,多 P 插画说中性的「用阅读器看」(#1029)。
 */
private fun comicReaderEnterTextRes(illust: Illust): Int =
    if ("manga" == illust.type) R.string.comic_reader_enter else R.string.comic_reader_enter_illust
