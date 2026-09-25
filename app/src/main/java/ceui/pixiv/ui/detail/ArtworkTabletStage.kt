package ceui.pixiv.ui.detail

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.common.resolveIllustThumbnailUrl
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.setTextWithIcon
import ceui.pixiv.witstudio.theme.shape
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import jp.wasabeef.glide.transformations.BlurTransformation
import java.util.Locale

/**
 * 平板作品详情的「作品舞台」（#1087，layout-sw600dp/fragment_artwork_v3.xml）。
 *
 * 手机版把作品图当列表顶部的大图条目，往下滚就滚走了；宽窗口下作品固定在舞台里、始终可见，
 * 信息栏在旁边（或下方）独立滚动：
 *
 * - **排布**（[arrange]）：窗口宽于高时舞台在左、信息栏在右（宽度取窗口 36%，夹在
 *   400–520dp）；否则舞台在上（半屏）、信息栏在下。信息栏压在整页环境色上，
 *   靠舞台一侧是 28dp 圆角。
 * - **作品**：上下贴满舞台（连状态栏下面也铺），按原比例居中、不裁切；横图放不下时按宽度
 *   放、上下露出环境色。多页作品上下翻页，侧边页码条可直接跳页，底部是「当前 / 总页」读数；
 *   动图原地播放。返回键、页码条、读数都浮在作品上并各自避开系统栏。点作品进全屏大图。
 * - **环境色**：同一张图的模糊放大铺满整页，压一层页面底色保证信息栏文字可读。
 *
 * 只管视图，不碰数据：作品 bean 由 [ArtworkV3Fragment] 从 ObjectPool 观察到后调 [bind]。
 */
internal class ArtworkTabletStage(
    private val fragment: ArtworkV3Fragment,
    private val stage: FrameLayout,
    private val infoColumn: FrameLayout,
    private val backdrop: ImageView,
    private val scrim: View,
    private val forceOriginal: () -> Boolean,
    private val onBack: () -> Unit,
    private val onMore: () -> Unit,
    private val onOpenReader: () -> Unit,
) {
    private val ctx = stage.context
    private val palette = V3Palette.from(ctx)

    /** true = 舞台在左、信息栏在右；false = 舞台在上、信息栏在下（信息栏不贴屏幕顶）。 */
    var sideBySide: Boolean = true
        private set

    private var illust: Illust? = null
    private var systemInsets: Insets = Insets.NONE

    private val content = FrameLayout(ctx).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val pager = ViewPager2(ctx).apply {
        orientation = ViewPager2.ORIENTATION_VERTICAL
        offscreenPageLimit = 1
        (getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
    }
    private var ugoira: UgoiraPlayerView? = null
    private val strip = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.VERTICAL, false)
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        // 浮在作品上的一条玻璃底：任何画面上缩略图边界都看得清
        background = shape(ctx.dpF(20f), SCRIM_PILL)
        setPadding(0, ctx.dp(10), 0, ctx.dp(2))
    }
    private val counter = ctx.label("", 12f, 600, Color.WHITE).apply {
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        background = shape(ctx.dpF(999f), SCRIM_PILL)
        fontFeatureSettings = "tnum"
    }
    private val readerPill = ctx.label("", 13f, 600, Color.WHITE).apply {
        gravity = Gravity.CENTER
        minHeight = ctx.dp(44)
        setPadding(ctx.dp(16), 0, ctx.dp(18), 0)
        background = ctx.ripple(shape(ctx.dpF(999f), SCRIM_PILL), shape(ctx.dpF(999f), Color.WHITE))
        isClickable = true
        isFocusable = true
        setOnClickListener { onOpenReader() }
        pressScale()
    }
    private val backButton = glassButton(R.drawable.ic_v3_chevron_left_24, R.string.reader_desc_back, onBack)
    private val moreButton = glassButton(R.drawable.ic_more_vert_black_24dp, R.string.more_actions, onMore)

    /** 浮在作品上的圆形玻璃按钮（返回 / 更多）：黑 45% 底 + 白图标，任何画面上都看得清。 */
    private fun glassButton(icon: Int, description: Int, action: () -> Unit) = ImageButton(ctx).apply {
        setImageResource(icon)
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(Color.WHITE))
        background = ctx.ripple(shape(ctx.dpF(999f), SCRIM_PILL), shape(ctx.dpF(999f), Color.WHITE))
        contentDescription = ctx.getString(description)
        setOnClickListener { action() }
        pressScale()
    }

    init {
        stage.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        stage.addView(backButton, FrameLayout.LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.TOP or Gravity.START))
        stage.addView(moreButton, FrameLayout.LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.TOP or Gravity.END))
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = onPageShown(position)
        })
        arrange()
        applyBackdropScrim()
        ViewCompat.setOnApplyWindowInsetsListener(stage) { _, windowInsets ->
            systemInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            applyInsets()
            windowInsets
        }
    }

    // ── 排布 ──────────────────────────────────────────────────────────────

    /**
     * 按当前窗口排舞台与信息栏。尺寸取 Configuration 的窗口 dp：宿主 Activity 随窗口尺寸
     * 变化重建，这里在首帧布局之前就能排好，不必等 layout 回调再改一遍参数。
     */
    private fun arrange() {
        val config = ctx.resources.configuration
        val widthDp = config.screenWidthDp
        val heightDp = config.screenHeightDp
        sideBySide = widthDp > heightDp
        val rtl = stage.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val radius = ctx.dpF(PANEL_RADIUS_DP)
        val panel = GradientDrawable().apply { setColor(panelColor()) }
        if (sideBySide) {
            val infoDp = (widthDp * SIDE_INFO_RATIO).toInt()
                .coerceIn(SIDE_INFO_MIN_DP, SIDE_INFO_MAX_DP)
                .coerceAtMost(widthDp / 2)
            infoColumn.layoutParams = FrameLayout.LayoutParams(ctx.dp(infoDp), MATCH, Gravity.END)
            stage.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH, Gravity.START).apply {
                marginEnd = ctx.dp(infoDp)
            }
            // 靠舞台那一侧（起始侧）圆角；顺序 tl, tr, br, bl
            panel.cornerRadii = if (rtl) {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            } else {
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            }
        } else {
            val stageDp = (heightDp * STACKED_STAGE_RATIO).toInt()
            stage.layoutParams = FrameLayout.LayoutParams(MATCH, ctx.dp(stageDp), Gravity.TOP)
            infoColumn.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP).apply {
                topMargin = ctx.dp(stageDp)
            }
            panel.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        infoColumn.background = panel
        infoColumn.clipToOutline = true
    }

    /** 信息栏底色：页面底色略透，环境色隐约透出，正文对比度仍按实底算（alpha 0.92）。 */
    private fun panelColor(): Int = withAlpha(ctx.color(R.color.v3_bg), PANEL_ALPHA)

    private fun applyBackdropScrim() {
        val bg = ctx.color(R.color.v3_bg)
        backdrop.alpha = BACKDROP_ALPHA
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(bg, 0.30f), withAlpha(bg, 0.55f)),
        )
    }

    private fun end(): Int =
        if (stage.layoutDirection == View.LAYOUT_DIRECTION_RTL) systemInsets.left else systemInsets.right

    /** 作品贴满舞台、不让系统栏；浮在上面的控件各自让开。 */
    private fun applyInsets() {
        val start = if (stage.layoutDirection == View.LAYOUT_DIRECTION_RTL) systemInsets.right else systemInsets.left
        val top = systemInsets.top
        val bottom = if (sideBySide) systemInsets.bottom else 0
        (backButton.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = top + ctx.dp(16)
            it.marginStart = start + ctx.dp(20)
            backButton.layoutParams = it
        }
        (moreButton.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = top + ctx.dp(16)
            // 并排时舞台结束侧贴着信息栏，不是屏幕边，不用让导航栏
            it.marginEnd = ctx.dp(20) + if (sideBySide) 0 else end()
            moreButton.layoutParams = it
        }
        (strip.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = top + ctx.dp(OVERLAY_EDGE_DP)
            it.bottomMargin = bottom + ctx.dp(OVERLAY_EDGE_DP)
            strip.layoutParams = it
        }
        (counter.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = bottom + ctx.dp(20)
            counter.layoutParams = it
        }
        (readerPill.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = bottom + ctx.dp(16)
            it.marginStart = start + ctx.dp(20)
            readerPill.layoutParams = it
        }
    }

    // ── 绑定 ──────────────────────────────────────────────────────────────

    fun bind(next: Illust) {
        val previous = illust
        illust = next
        // 只在「决定画面的字段」变了时重建：列表页带来的精简 bean 可能还没有图片地址 / 宽高，
        // 完整详情落池后要重新出图；收藏、关注这类变化不该让舞台重新取图
        if (previous != null && visualKey(previous) == visualKey(next)) return
        Glide.with(fragment)
            .load(GlideUtil.getSquare(next))
            .transform(BlurTransformation(BACKDROP_BLUR_RADIUS, BACKDROP_BLUR_SAMPLING))
            .into(backdrop)
        content.removeAllViews()
        if (next.isGif()) bindUgoira(next) else bindPages(next)
    }

    private fun bindPages(illust: Illust) {
        releaseUgoira()
        val count = illust.page_count.coerceAtLeast(1)
        val multi = count > 1
        content.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))
        pager.adapter = PageAdapter(illust, count)
        pager.setCurrentItem(0, false)

        if (multi) {
            content.addView(strip, FrameLayout.LayoutParams(ctx.dp(STRIP_WIDTH_DP), WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = ctx.dp(STRIP_EDGE_DP)
            })
            strip.adapter = StripAdapter(illust, count)
            content.addView(counter, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        if (CollapsibleIllustAdapter.shouldCollapse(count)) {
            readerPill.setTextWithIcon(
                ctx.getString(
                    if ("manga" == illust.type) R.string.comic_reader_enter else R.string.comic_reader_enter_illust
                ),
                R.drawable.ic_v3_chevron_24,
            )
            content.addView(readerPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
        }
        applyInsets()
        onPageShown(0)
    }

    private fun bindUgoira(illust: Illust) {
        pager.adapter = null
        val player = ugoira ?: UgoiraPlayerView(ctx).also { ugoira = it }
        content.addView(player, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        // 等舞台排好版才知道能给多高；这一拍里页面可能已经关了（视图销毁、播放器已 release），
        // 那时再取 viewLifecycleOwner 会抛异常、再 bind 会让已释放的播放器重新跑起来
        content.post {
            if (fragment.view == null || ugoira !== player || this.illust?.id != illust.id) return@post
            player.bind(fragment.viewLifecycleOwner, illust, content.height.coerceAtLeast(ctx.dp(200)))
            player.onFeedAttached()
        }
        player.setOnClickListener { openUgoiraViewer(it, illust) }
    }

    private fun visualKey(illust: Illust): List<Any?> = listOf(
        illust.id,
        illust.page_count,
        illust.isGif(),
        illust.width,
        illust.height,
        illust.image_urls?.large,
        illust.meta_pages?.size,
    )

    /** 菜单「加载原图」之后：按新的清晰度重新取图。 */
    fun reloadPages() {
        val current = illust ?: return
        if (current.isGif()) return
        val position = pager.currentItem
        pager.adapter = PageAdapter(current, current.page_count.coerceAtLeast(1))
        pager.setCurrentItem(position, false)
    }

    fun release() {
        releaseUgoira()
        pager.adapter = null
        strip.adapter = null
    }

    private fun releaseUgoira() {
        ugoira?.let {
            it.onFeedDetached()
            it.recycle()
        }
        ugoira = null
    }

    private fun onPageShown(position: Int) {
        val count = illust?.page_count?.coerceAtLeast(1) ?: return
        counter.text = String.format(Locale.US, "%d / %d", position + 1, count)
        (strip.adapter as? StripAdapter)?.select(position)
        if (count > 1) strip.smoothScrollToPosition(position)
    }

    private fun pageUrl(illust: Illust, index: Int): GlideUrl? {
        val original = Shaft.sSettings.isShowOriginalPreviewImage || forceOriginal()
        return when {
            original -> GlideUtil.getOriginalImage(illust, index)
            illust.page_count > 1 -> GlideUtil.getLargeImage(illust, index)
            else -> GlideUtil.getLargeImage(illust)
        }
    }

    private fun openViewer(illust: Illust, index: Int, from: View) {
        val location = IntArray(2)
        from.getLocationOnScreen(location)
        fragment.startActivity(Intent(ctx, ImageDetailActivity::class.java).apply {
            putExtra("illust", illust)
            putExtra("dataType", "二级详情")
            putExtra("index", index)
            putExtra(
                ImageDetailActivity.EXTRA_ENTER_BOUNDS,
                intArrayOf(location[0], location[1], location[0] + from.width, location[1] + from.height),
            )
        })
    }

    // ── 作品页 ─────────────────────────────────────────────────────────────

    /** 把唯一的子 view 按 [ratio]（高/宽）完整放进自身，居中。测量阶段算好，不在布局回调里改参数。 */
    private class RatioFitFrame(context: android.content.Context) : FrameLayout(context) {
        var ratio: Float = 1f
            set(value) {
                if (field != value && value > 0f) {
                    field = value
                    requestLayout()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(width, height)
            var childWidth = width
            var childHeight = (width * ratio).toInt()
            if (childHeight > height) {
                childHeight = height
                childWidth = (height / ratio).toInt()
            }
            getChildAt(0)?.measure(
                MeasureSpec.makeMeasureSpec(childWidth.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight.coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val child = getChildAt(0) ?: return
            val x = (width - child.measuredWidth) / 2
            val y = (height - child.measuredHeight) / 2
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    private inner class PageAdapter(
        private val illust: Illust,
        private val count: Int,
    ) : RecyclerView.Adapter<PageAdapter.Holder>() {

        inner class Holder(val frame: RatioFitFrame, val image: ImageView) : RecyclerView.ViewHolder(frame)

        override fun getItemCount(): Int = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = true
                isFocusable = true
            }
            val frame = RatioFitFrame(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                clipChildren = false
                clipToPadding = false
                addView(image)
            }
            return Holder(frame, image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            // 其余页的比例拿不到，先按首页比例占位，图到了再按真实比例重排
            holder.frame.ratio = firstPageRatio(illust)
            holder.image.contentDescription = String.format(Locale.US, "%s %d / %d", illust.title.orEmpty(), position + 1, count)
            holder.image.setOnClickListener { view ->
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) openViewer(illust, index, view)
            }
            // 大图可能要好几秒：先用列表卡片那张已在缓存里的缩略图垫底，点进来立刻有画面
            val pageIndex = position
            val thumbnail = if (pageIndex == 0) {
                resolveIllustThumbnailUrl(illust)
            } else {
                illust.meta_pages?.getOrNull(pageIndex)?.image_urls?.medium?.let(GlideUtil::getUrl)
            }
            Glide.with(fragment)
                .load(pageUrl(illust, pageIndex))
                .thumbnail(Glide.with(fragment).load(thumbnail))
                .into(object : CustomViewTarget<ImageView, Drawable>(holder.image) {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        // 首页的宽高 bean 里就有，别让（可能是方形的）缩略图改比例；其余页按图到手的真实比例
                        val w = resource.intrinsicWidth
                        val h = resource.intrinsicHeight
                        if (w > 0 && h > 0 && (pageIndex > 0 || illust.width <= 0 || illust.height <= 0)) {
                            holder.frame.ratio = h.toFloat() / w
                        }
                        holder.image.setImageDrawable(resource)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        // 大图失败时别把已经垫上的缩略图清掉：没有错误图就留着它（点开全屏会重新取原图）
                        if (errorDrawable != null) holder.image.setImageDrawable(errorDrawable)
                    }

                    override fun onResourceCleared(placeholder: Drawable?) {
                        holder.image.setImageDrawable(placeholder)
                    }
                })
        }

        private fun firstPageRatio(illust: Illust): Float =
            if (illust.width > 0 && illust.height > 0) illust.height.toFloat() / illust.width else 1f
    }

    /** 侧边页码条：点缩略图跳页，当前页主题色细边、其余略暗。 */
    private inner class StripAdapter(
        private val illust: Illust,
        private val count: Int,
    ) : RecyclerView.Adapter<StripAdapter.Holder>() {

        private var selected = 0

        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        fun select(index: Int) {
            if (index == selected) return
            val old = selected
            selected = index
            notifyItemChanged(old)
            notifyItemChanged(index)
        }

        override fun getItemCount(): Int = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val size = ctx.dp(STRIP_THUMB_DP)
            val radius = ctx.dpF(12f)
            val image = ImageView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(size, size).apply {
                    bottomMargin = ctx.dp(8)
                    marginStart = (ctx.dp(STRIP_WIDTH_DP) - size) / 2
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                clipToOutline = true
                setBackgroundColor(ctx.color(R.color.v3_surface_2))
                isClickable = true
                isFocusable = true
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val isSelected = position == selected
            holder.image.foreground = if (isSelected) {
                GradientDrawable().apply {
                    cornerRadius = ctx.dpF(12f)
                    setStroke(ctx.dp(2), palette.primary)
                }
            } else {
                null
            }
            holder.image.alpha = if (isSelected) 1f else 0.6f
            holder.image.contentDescription = String.format(Locale.US, "%d / %d", position + 1, count)
            holder.image.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) pager.currentItem = index
            }
            Glide.with(fragment)
                .load(illust.meta_pages?.getOrNull(position)?.image_urls?.square_medium?.let(GlideUtil::getUrl))
                .into(holder.image)
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val SIDE_INFO_RATIO = 0.36f
        const val SIDE_INFO_MIN_DP = 400
        const val SIDE_INFO_MAX_DP = 520
        const val STACKED_STAGE_RATIO = 0.52f
        const val PANEL_RADIUS_DP = 28f
        const val PANEL_ALPHA = 0.92f

        const val STRIP_WIDTH_DP = 72
        const val STRIP_THUMB_DP = 52
        const val STRIP_EDGE_DP = 12
        const val OVERLAY_EDGE_DP = 72

        const val BACKDROP_ALPHA = 0.9f
        const val BACKDROP_BLUR_RADIUS = 25
        const val BACKDROP_BLUR_SAMPLING = 4

        /** 浮在作品/环境色上的胶囊底：黑 45%，白字在任何作品上都读得出。 */
        const val SCRIM_PILL = 0x73000000

        fun withAlpha(color: Int, alpha: Float): Int =
            (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
    }
}
