package ceui.pixiv.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.lisa.databinding.RecyIllustStaggerBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.recommend.bindTrendingScore
import ceui.pixiv.utils.playLikePressHaptic
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import jp.wasabeef.glide.transformations.BlurTransformation
import java.util.Locale

/** 瀑布流卡片高度钳制（对齐 legacy IAdapter）：高 = 宽的 0.6~2.0 倍。 */
private const val MIN_HEIGHT_RATIO = 0.6f
private const val MAX_HEIGHT_RATIO = 2.0f

/** 屏蔽态的 Glide 模糊参数（对齐画师头图 [ceui.pixiv.ui.muted] 那套 25/3，够糊到认不出内容）。 */
private const val SPOILER_BLUR_RADIUS = 25
private const val SPOILER_BLUR_SAMPLING = 3

/**
 * 「屏蔽此作品」态变化的局部重绑 payload（按引用识别）。
 *
 * 和 [PAYLOAD_ILLUST_LIKE_CHANGED] 不同，它**不来自 DiffUtil**：屏蔽态的真源是
 * [IllustSpoilerStore]（设备本地名单），不在 [IllustFeedItem] 上，条目内容压根没变。
 * 由 [IllustFeedFragment.setIllustSpoilered] 直接 `notifyItemChanged(pos, payload)` 派发。
 *
 * 为什么屏蔽态不进条目：进了就得让每个 `IllustFeedItem` 构造点都带上它、还要在
 * [IllustFeedItem.withBookmarked] 里手动接力；更要命的是**别的页面早已构造好的条目会揣着
 * 过期值**（同一作品在收藏页/搜索页各有一份条目实例）。放名单里、bind 时现读，任何页面滑动
 * 复用一次就自动同步。
 */
internal val PAYLOAD_ILLUST_SPOILER_CHANGED = Any()

/**
 * Glide 请求去重 key（存在 ImageView 的 tag 上）。带 [blurred]：屏蔽态切换时 URL 和尺寸都没变，
 * 少了这一维就会因为 key 相等而跳过重新加载，图永远不糊/不清。
 */
private data class IllustImageRequestKey(
    val cacheKey: String?,
    val width: Int,
    val height: Int,
    val blurred: Boolean,
)

/**
 * 标准瀑布流插画卡（recy_illust_stagger，与 legacy IAdapter 同一张布局同一套行为）：
 * NP 页数 / GIF / R-18 / AI / NEW 角标、爱心收藏（局部重绑）、爱心长按进「按标签收藏」、
 * 点击开详情、长按弹卡片菜单，比例钳制 0.6~2.0。
 *
 * 从 [IllustFeedFragment] 搬出来单独放一个文件（对齐 [ceui.pixiv.ui.comments.commentCardRenderer]
 * 的既有做法）：「数据怎么画」不挤在 Fragment 里，Fragment 只管编排。
 *
 * 是 [IllustFeedFragment] 的扩展函数而非顶层函数：renderer 只在 `onCreateRenderers()` 里建一次、
 * 随 view 生死（[FeedFragment.onDestroyView] 清 adapter），引用 Fragment 安全 —— 不违反
 * `feedViewModels` 的零捕获约定（那条约定管的是 VM 长期持有的 FeedSource / mapper）。
 */
internal fun IllustFeedFragment.staggerIllustRenderer(
    showSpoilerParticles: Boolean = false,
):
        FeedRenderer<IllustFeedItem, RecyIllustStaggerBinding> =
    feedRenderer<IllustFeedItem, RecyIllustStaggerBinding>(
        inflate = RecyIllustStaggerBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                // 已屏蔽的卡先「揭开」再说（对齐 Telegram spoiler：点一下露出内容），不直接开详情——
                // 否则「屏蔽」等于没屏蔽，手一滑就把刚遮住的东西整屏铺开了。取消屏蔽的另一个入口
                // 是长按菜单同一项（标签会变成「取消屏蔽」）。
                if (IllustSpoilerStore.isSpoilered(tapped.illust.id)) {
                    setIllustSpoilered(tapped.illust.id, false)
                } else {
                    openDetail(tapped)
                }
            }
            cell.binding.root.setOnLongClickListener {
                showCardMenu(cell.item)
                true
            }
            cell.binding.likeButton.setOnClick {
                // 收藏态的真源是 VM 的当前状态，不是 cell.item —— 后者是 adapter **已提交的快照**，
                // 要等 ListAdapter 后台 diff 落地才经 cell.attach 换新（下面那段注释自己写了「至少
                // 1~2 帧，大列表更久」）。而 uiState.value 是同步就绪的。读 cell.item 的后果：连点
                // 两下时第二下仍看到上一下之前的旧态，把「取消收藏」反转成「再收藏一次」——心不回白、
                // 烟花重放、两条 toast、两次 addBookmark，取消这个操作直接丢失。
                val tapped = cell.itemOrNull ?: return@setOnClick
                val current = currentIllustItem(tapped.illust.id) ?: return@setOnClick
                val willBookmark = current.illust.is_bookmarked != true
                toggleLike(current)
                // 乐观重绑要等 ListAdapter 后台 diff 落地（至少 1~2 帧，大列表更久），
                // 静态爱心必须当帧先切到目标态：否则爆发动画开头几帧红心还小，
                // 底下过期的白色空心心会从边缘漏出来；随后的 payload 重绑是幂等兜底
                renderLikeState(cell.binding.likeButton, willBookmark)
                if (willBookmark) {
                    playLikeBurst(cell.binding)
                } else {
                    playUnlikeShrink(cell.binding.likeButton)
                }
            }
            // 动画播完/被取消都收起爆发层，露出下面已经变红的静态爱心
            cell.binding.likeAnim.addAnimatorListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cell.binding.likeAnim.isVisible = false
                }
            })
            // lottie 默认 failure listener 会直接抛 IllegalStateException 崩 app；
            // 动画资源解析失败就静默降级成无动画（静态爱心链路完全不受影响）
            cell.binding.likeAnim.setFailureListener { }
            // 爱心长按 → 按标签收藏（对齐 IAdapter）
            cell.binding.likeButton.setOnLongClickListener {
                val bean = cell.item.bean
                SelectTagBottomSheet.show(
                    this@staggerIllustRenderer, bean.id, Params.TYPE_ILLUST, bean.tagNames,
                )
                true
            }
        },
        recycle = { cell ->
            // 淡入淡出可能正跑到一半就被回收：连 animator 一起停掉并把 alpha 归位，
            // 否则复用到下一条目时粒子层带着半透明残值出场
            cell.binding.spoilerParticles.animate().cancel()
            cell.binding.spoilerParticles.alpha = 1f
            cell.binding.spoilerParticles.setParticleAnimationRunning(false)
            illustGlide.clear(cell.binding.illustImage)
            cell.binding.illustImage.tag = null
            resetLikeAnim(cell.binding)
        },
        attach = { cell ->
            val spoilered = cell.itemOrNull?.let { IllustSpoilerStore.isSpoilered(it.illust.id) }
            if (showSpoilerParticles || spoilered == true) {
                cell.binding.spoilerParticles.setParticleAnimationRunning(true)
            }
        },
        detach = { cell ->
            cell.binding.spoilerParticles.setParticleAnimationRunning(false)
        },
        changePayload = ::illustLikeChangePayload,
        bindPayloads = { cell, payloads ->
            // RecyclerView 会把同一帧内的多个 payload 攒在一起给同一个 holder，
            // 所以逐个认领而不是二选一；混进不认识的就整体退回全量绑定
            val known = payloads.all {
                it === PAYLOAD_ILLUST_LIKE_CHANGED || it === PAYLOAD_ILLUST_SPOILER_CHANGED
            }
            if (known) {
                if (payloads.any { it === PAYLOAD_ILLUST_LIKE_CHANGED }) {
                    renderLikeState(cell.binding.likeButton, cell.item.illust.is_bookmarked == true)
                }
                if (payloads.any { it === PAYLOAD_ILLUST_SPOILER_CHANGED }) {
                    val bean = cell.item.bean
                    // 一律用 illust.id（Long）当名单 key：legacy bean.id 是 int，
                    // 两边混着用早晚会在同一份名单里对不上号
                    val spoilered = IllustSpoilerStore.isSpoilered(cell.item.illust.id)
                    loadIllustImage(cell.binding, bean, spoilered)
                    // 这条路径是用户刚点下「屏蔽 / 揭开」的那一下：粒子淡进淡出，
                    // 跟 Glide 换图的 crossfade 同步，不要硬切
                    renderSpoilerParticles(
                        cell.binding.spoilerParticles,
                        show = showSpoilerParticles || spoilered,
                        animate = true,
                    )
                }
                true
            } else {
                false
            }
        },
    ) { cell ->
        val bean = cell.item.bean
        cell.binding.illustImage.setHeightRatio(heightRatioOf(bean))

        // 屏蔽态的真源是设备本地名单，bind 时现读：别的页面屏蔽了同一作品，本页滑动复用一次
        // 就跟上了（条目本身不带这个状态，见 PAYLOAD_ILLUST_SPOILER_CHANGED 的注释）
        val spoilered = IllustSpoilerStore.isSpoilered(cell.item.illust.id)
        // 粒子层：首页推荐全员开（showSpoilerParticles），其余页面只给被屏蔽的卡开。
        // View 本身不 clickable，不会截走卡片点击/长按。
        // 预取阶段即使完成 bind，也要等 holder attach 后才真正开始逐帧模拟。
        renderSpoilerParticles(
            cell.binding.spoilerParticles,
            show = showSpoilerParticles || spoilered,
            animate = false,
        )
        loadIllustImage(cell.binding, bean, spoilered)

        cell.binding.pSize.isVisible = bean.page_count > 1
        if (bean.page_count > 1) {
            cell.binding.pSize.text = String.format(Locale.getDefault(), "%dP", bean.page_count)
        }
        cell.binding.pGif.isVisible = bean.isGif
        cell.binding.r18Badge.isVisible = bean.isR18File
        cell.binding.createdByAi.isVisible = bean.isCreatedByAI
        cell.binding.pRelated.isVisible = bean.isRelated
        // 只有 trending repo 注入 trendingScore，其他页 null 走 GONE（对齐 IAdapter 复用语义）
        cell.binding.trendingScore.bindTrendingScore(bean.trendingScore)
        // 全量绑定可能是复用的卡换了条目：上一条残留的爆发动画/缩放必须清干净
        resetLikeAnim(cell.binding)
        cell.binding.likeButton.isVisible = !hideLikeButton
        renderLikeState(cell.binding.likeButton, cell.item.illust.is_bookmarked == true)
    }

/**
 * 只按元数据驱动宽高比（钳到宽的 0.6~2.0 倍，对齐 IAdapter），不等 Glide 量像素；
 * 宽度交给瀑布流列自身，DynamicHeightImageView 在 onMeasure 用真实列宽算高——
 * 绝不写死像素尺寸，否则复用卡片在横竖屏切换后揣着旧方向的尺寸把整列搞乱。
 */
private fun heightRatioOf(bean: IllustsBean): Float {
    return if (bean.width > 0 && bean.height > 0) {
        (bean.height.toFloat() / bean.width.toFloat())
            .coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO)
    } else {
        1f
    }
}

/**
 * 卡片图加载。全量绑定和「屏蔽态切换」的局部重绑共用同一条路径，两边参数必须逐字一致 ——
 * 否则局部重绑刚换的图会被下一次全量绑定按不同的 key 再拉一遍（白闪一次）。
 */
private fun IllustFeedFragment.loadIllustImage(
    binding: RecyIllustStaggerBinding,
    bean: IllustsBean,
    spoilered: Boolean,
) {
    val imgUrl = if (Shaft.sSettings.isShowLargeThumbnailImage()) {
        GlideUtil.getLargeImage(bean)
    } else {
        GlideUtil.getMediumImg(bean)
    }
    // 请求尺寸必须显式 override：into(ImageView) 对 centerCrop 会在解码阶段按「请求尺寸」
    // 的宽高比裁位图，而默认请求尺寸取复用卡片上一次布局残留的旧宽高（旧方向的列宽 ×
    // 上一张图的比例），横竖屏来回切后图会被裁得只剩一小块还发糊，且 view 重新量高后
    // Glide 不会重发请求。override 成当前列宽 × 钳制后比例，请求宽高比恒等于展示宽高比。
    val columnWidth = illustColumnWidthPx
    val columnHeight = (columnWidth * heightRatioOf(bean)).toInt()
    // GlideUrlChild 每次构造都带当前时间戳请求头（PixivHeaders.x-client-time/hash），
    // 而 GlideUrl.equals() 要求 headers 也相等才算「同一请求」——这里的 headers 又是个
    // 没重写 equals 的 lambda，Glide 自己的活跃资源缓存永远认不出「这张图已经在显示」。
    // 本地优先冷启（缓存快照 → 网络新数据）时 Illust.total_view/total_bookmarks 几乎
    // 必然变了（这两个字段卡片根本不展示），触发全量重绑，重绑一律重新发 Glide 请求，
    // 于是每张卡片的图都要闪一次占位色再淡入回来——图其实没变。用请求 URL（不含
    // headers 的 cacheKey）+ 目标尺寸 + 模糊与否当 tag，真没变时跳过这次重新加载；
    // recycle 清图时一并清 tag，保证真正复用到新条目时不会因为 tag 恰好没变而漏加载。
    val requestKey = IllustImageRequestKey(imgUrl?.cacheKey, columnWidth, columnHeight, spoilered)
    if (binding.illustImage.tag == requestKey) return
    binding.illustImage.tag = requestKey

    // 这里曾经挂过一个 .error(...) 兜底：它加载的是**同一个 imgUrl**，参数逐字相同 ——
    // 也就是把刚失败的请求原样再发一遍。而 .error() 收的是已经建好的 RequestBuilder，
    // 参数急切求值，于是 100% 的加载都要多付一整条 builder + 一次 Glide.with 的代价，
    // 只有「瞬时网络抖动」这不到 1% 的情形能受益（404 则是稳定失败两次）。已删。
    var request = illustGlide
        .load(imgUrl)
        .override(columnWidth, columnHeight)
    if (spoilered) {
        // 屏蔽态让 Glide 直接出一张模糊位图：变换进 cacheKey，与原图各存各的，滚回来是缓存命中。
        // 不用 View 层模糊（RenderEffect / 自绘）——那类做法在瀑布流复用里每帧都要重算，
        // 而这里的内容是静态的，糊一次存起来就够了。
        request = request.apply(
            bitmapTransform(BlurTransformation(SPOILER_BLUR_RADIUS, SPOILER_BLUR_SAMPLING))
        )
    }
    request
        // 占位底色对齐白天骨架图（feed_skeleton_block），未加载卡不再白页糊一片
        .placeholder(R.color.feed_skeleton_block)
        .transition(DrawableTransitionOptions.withCrossFade())
        .into(binding.illustImage)
}

/** 未收藏 = 白色空心描边爱心，已收藏 = 红色实心爱心（图上永远配深色圆底座）。 */
internal fun renderLikeState(button: ImageView, liked: Boolean) {
    button.setImageResource(
        if (liked) R.drawable.ic_like_heart_fill else R.drawable.ic_like_heart_outline
    )
    button.imageTintList = ColorStateList.valueOf(
        if (liked) {
            ContextCompat.getColor(button.context, R.color.has_bookmarked)
        } else {
            Color.WHITE
        }
    )
}

/**
 * 收藏爆发动画：静态爱心由点击处当帧切红（异步乐观重绑只是兜底，等它就晚了），
 * 上层 72dp Lottie 播弹性爱心 + 白闪 + 冲击波圆环 + 三色粒子向作品图上炸开，
 * 播完自动收起。动画层非 clickable，播放中不挡按钮点击；局部重绑只动静态爱心，
 * 不打断动画。
 */
private fun playLikeBurst(binding: RecyIllustStaggerBinding) {
    playLikePressHaptic(binding.likeButton)
    binding.likeAnim.apply {
        isVisible = true
        progress = 0f
        playAnimation()
    }
}

/** 取消收藏：静态爱心一个干脆的回弹缩放，不放烟花；触感只给单下轻 tick。 */
private fun playUnlikeShrink(button: ImageView) {
    button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    button.animate().cancel()
    button.scaleX = 0.6f
    button.scaleY = 0.6f
    button.animate()
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(280L)
        .setInterpolator(OvershootInterpolator(2.5f))
        .start()
}

private fun resetLikeAnim(binding: RecyIllustStaggerBinding) {
    binding.likeAnim.cancelAnimation()
    binding.likeAnim.isVisible = false
    binding.likeButton.animate().cancel()
    binding.likeButton.scaleX = 1f
    binding.likeButton.scaleY = 1f
}
