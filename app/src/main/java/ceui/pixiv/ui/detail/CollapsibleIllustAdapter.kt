package ceui.pixiv.ui.detail

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
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
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.BarUtils

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
    private val illust: Illust,
    maxHeight: Int,
    isForceOriginal: Boolean,
    private val collapsedCount: Int = DEFAULT_COLLAPSED,
    var onComicReaderClick: (() -> Unit)? = null,
    var onExpandedChanged: ((expanded: Boolean) -> Unit)? = null,
    /**
     * 长按「展开剩余 X 张」→ 多图预览(#1085)。折叠态下这枚 CTA 才是用户眼里「这里还有几张」
     * 的落点,跟右上角的阅读胶囊是同一个诉求的两个入口,手势保持一致(都是长按)。
     * 返回是否真的弹出了预览 —— 没弹就别把这次长按吃掉,单击展开照常。
     */
    var onExpandPillLongClick: (() -> Boolean)? = null,
) : IllustAdapter(activity, fragment, illust, maxHeight, isForceOriginal) {

    private var expanded = false

    /** super 里 [maxHeight] 是 private,这里留一份给折叠封面兜高用(见 [floorCoverHeight])。 */
    private val coverMaxHeight: Int = maxHeight

    val totalPages: Int get() = illust.page_count
    val hiddenCount: Int get() = (totalPages - collapsedCount).coerceAtLeast(0)
    val isCollapsible: Boolean get() = totalPages > collapsedCount
    val isCollapsed: Boolean get() = !expanded && isCollapsible
    val isExpanded: Boolean get() = expanded && isCollapsible

    override fun getItemCount(): Int {
        val total = super.getItemCount()
        return if (expanded) total else minOf(total, collapsedCount)
    }

    fun expand() {
        if (expanded) return
        // 展开时再扫一遍下载库：覆盖「未展开时下载、随后展开」——此时第 2 张及之后
        // 才首次绑定，扫到本地文件就直读，不回 pixiv 重新下。
        scanLocalDownloads()
        val prev = itemCount
        expanded = true
        val added = itemCount - prev
        if (added > 0) notifyItemRangeInserted(prev, added)
        // No notifyItemChanged(0) here — the caller's fade-out on the scrim
        // would get clobbered. The next natural rebind will hide the overlay.
        onExpandedChanged?.invoke(true)
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

    override fun onBindViewHolder(holder: ViewHolder<RecyIllustDetailBinding>, position: Int) {
        super.onBindViewHolder(holder, position)
        bindExpandOverlay(holder, position, fadeIn = false)
    }

    /**
     * 兜高统一挂在 super 的展示盒入口上:绑定期(bean 宽高)与 renderOverlay 原图落地后的真尺寸
     * 校准都会走到这里,两条路径口径一致。此前兜高只在绑定后补一次:原图校准重设自然 ratio 把它
     * 清掉(首进详情页封面塌回自然高),滚走再滚回时 rebind 重新兜高、校准又按页去重不再触发 →
     * 同一封面首进与回滚后高度不一致。
     */
    override fun applyPixelSize(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        w: Int,
        h: Int,
    ) {
        super.applyPixelSize(holder, position, w, h)
        // 全景页(超宽图)的盒高已由 super 定下(固定高 + 横向可拖),不再套封面兜高:
        // 兜高会把它拔回 maxHeight 的大黑盒,正是全景要替代的观感。
        if (position == 0 && isCollapsible && !isPanoramaPage(position)) {
            floorCoverHeight(holder, w, h)
        }
    }

    /**
     * 极端宽比封面(如 8400×2000)的自然高度会缩成顶部一条窄缝:图挤在状态栏/返回键底下,
     * 「展开剩余 X 张」胶囊([R.id.expand_overlay] alignBottom illust)也顶进 toolbar 里被吃掉
     * 点击(见反馈截图 #5)。这里把这种窄封面直接拔到 [coverMaxHeight]——与单图封面同一套:
     * super 已设 FIT_CENTER,宽图在高盒里整体居中,顶栏落在干净的上黑边、胶囊沉到封面底部,
     * 观感对齐 #6。仅对「自然高度 < 顶栏净空」的宽封面生效;正常/竖封面自然高度远大于阈值,
     * 原样不动、依旧无黑边贴顶。
     */
    private fun floorCoverHeight(holder: ViewHolder<RecyIllustDetailBinding>, w: Int, h: Int) {
        val image = holder.baseBind.illust
        if (w <= 0 || h <= 0) return
        // ratio 驱动下 layoutParams.height 只是占位(240dp),真实自然高 = 屏宽 × 高/宽。
        // imageSize 由 super(AbstractIllustAdapter) 持有(protected)。
        val natural = (imageSize.toLong() * h / w).toInt()
        // 自然高度已能让胶囊落在顶栏之下 → 保持贴顶无黑边的默认观感,不动。
        if (natural <= 0 || natural >= coverToolbarClearance()) return
        // 极端宽封面:关掉 ratio 自 measure,拔到 maxHeight 固定高、FIT_CENTER 居中,给足上黑边托住 toolbar。
        val target = if (coverMaxHeight > 0) coverMaxHeight else coverToolbarClearance()
        image.setHeightRatio(0f)
        // 兜高后 illust_hd 同样关 ratio,靠对齐跟随 illust 盒子,避免回收重绑残留旧 ratio 导致 overlay 错位。
        holder.baseBind.illustHd.setHeightRatio(0f)
        val lp = image.layoutParams ?: return
        if (lp.height != target) {
            lp.height = target
            image.layoutParams = lp
        }
    }

    /**
     * 顶栏净空阈值 = 状态栏 + [COVER_CLEARANCE_BELOW_STATUS_DP]。
     * 自然封面高于它就说明胶囊本就落在 toolbar 之下,无需兜高;低于它才判为「极端窄封面」。
     */
    private fun coverToolbarClearance(): Int =
        BarUtils.getStatusBarHeight() + COVER_CLEARANCE_BELOW_STATUS_DP.ppppx

    override fun onBindViewHolder(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        when {
            payloads.contains(PAYLOAD_OVERLAY_FADE_IN) ->
                bindExpandOverlay(holder, position, fadeIn = true)
            payloads.contains(PAYLOAD_OVERLAY_ONLY) ->
                bindExpandOverlay(holder, position, fadeIn = false)
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindExpandOverlay(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        fadeIn: Boolean,
    ) {
        val views = overlayOf(holder) ?: return
        val overlay = views.overlay
        val pill = views.expandPill
        val label = views.expandLabel
        val comicPill = views.comicPill

        if (position != 0 || !isCollapsed) {
            overlay.animate().cancel()
            overlay.visibility = View.GONE
            overlay.alpha = 1f
            pill.animate().cancel()
            pill.scaleX = 1f
            pill.scaleY = 1f
            pill.setOnClickListener(null)
            pill.setOnTouchListener(null)
            comicPill.visibility = View.GONE
            comicPill.setOnClickListener(null)
            return
        }

        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        if (fadeIn) {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            overlay.alpha = 1f
        }
        label.text = holder.itemView.context.getString(
            R.string.v3_expand_all_pages_title, hiddenCount
        )

        // 阅读器胶囊：多 P 插画同样适用(#1029),只是文案换成中性的「用阅读器看」——
        // 阅读器内部对 type 没有任何假设,系列跳话在插画没系列时会自己 toast 提示。
        // 注意只放宽胶囊,不放宽「点图进阅读器」:那条必须留着判 manga,否则 2P 插画
        // 点大图又会被劫持进阅读器(#961)。
        val hasReader = onComicReaderClick != null
        comicPill.visibility = if (hasReader) View.VISIBLE else View.GONE
        if (hasReader) {
            views.comicPillLabel.setText(comicReaderEnterTextRes(illust))
            applyPillTouchFeedback(comicPill)
            comicPill.setOnClickListener { onComicReaderClick?.invoke() }
        }

        // Press-down feedback — scale on ACTION_DOWN, restore on release/cancel.
        // Return false so the click still fires normally via setOnClickListener.
        applyPillTouchFeedback(pill)
        pill.setOnLongClickListener { v ->
            if (onExpandPillLongClick?.invoke() != true) return@setOnLongClickListener false
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        pill.setOnClickListener {
            // Kick off the data change FIRST so onExpandedChanged(true) fires
            // before the fade — the host pill fades IN concurrently with this
            // scrim fading OUT, instead of after.
            expand()
            overlay.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun applyPillTouchFeedback(view: View) {
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

    private class OverlayViews(
        val overlay: FrameLayout,
        val expandPill: View,
        val expandLabel: TextView,
        val comicPill: View,
        val comicPillLabel: TextView,
    )

    private fun overlayOf(holder: ViewHolder<RecyIllustDetailBinding>): OverlayViews? {
        val root = holder.itemView
        (root.getTag(TAG_OVERLAY) as? OverlayViews)?.let { return it }
        val overlay = root.findViewById<FrameLayout>(R.id.expand_overlay) ?: return null
        val pill = root.findViewById<View>(R.id.expand_pill) ?: return null
        val label = root.findViewById<TextView>(R.id.expand_label) ?: return null
        val comicPill = root.findViewById<View>(R.id.comic_reader_pill) ?: return null
        val comicPillLabel = root.findViewById<TextView>(R.id.comic_reader_pill_label) ?: return null
        val views = OverlayViews(overlay, pill, label, comicPill, comicPillLabel)
        root.setTag(TAG_OVERLAY, views)
        return views
    }

    override fun onViewAttachedToWindow(holder: ViewHolder<RecyIllustDetailBinding>) {
        super.onViewAttachedToWindow(holder)
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
            lp.isFullSpan = true
            holder.itemView.layoutParams = lp
        }
    }

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

        private val PAYLOAD_OVERLAY_ONLY = Any()
        private val PAYLOAD_OVERLAY_FADE_IN = Any()
        private val TAG_OVERLAY = R.id.expand_overlay
        private const val FADE_MS = 220L
    }
}

/**
 * 进阅读器入口的文案:漫画说「阅读漫画」,多 P 插画说中性的「用阅读器看」(#1029)。
 */
private fun comicReaderEnterTextRes(illust: Illust): Int =
    if ("manga" == illust.type) R.string.comic_reader_enter else R.string.comic_reader_enter_illust
