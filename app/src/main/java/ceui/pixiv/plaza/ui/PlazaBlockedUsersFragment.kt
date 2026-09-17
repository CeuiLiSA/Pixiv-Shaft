package ceui.pixiv.plaza.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.feeds.*
import ceui.pixiv.witstudio.theme.*

/**
 * 广场屏蔽名单，V3「管理」配方：一句说明卡 → 紧凑的记录列表 → 每条一个解除动作。
 *
 * 分页、下拉刷新、首屏骨架、空态与失败重试由 feeds 框架负责；这里只管顶栏、720dp 限宽、
 * 说明卡和条目长相。列表不做条目动画（与下载管理同一取舍）。
 */
class PlazaBlockedUsersFragment : FeedFragment(R.layout.fragment_plaza_blocks) {
    private val controller: PlazaBlocksController by viewModels()
    override val feedViewModel by feedViewModels { controller }

    // 说明已经常驻在列表上方的卡片里，空态只说「现在没有记录」这一件事。
    override val emptyStateText: CharSequence
        get() = getString(R.string.plaza_blocks_empty)

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        feedRenderer<PlazaBlockedUserItem, PlazaBlockedUserBinding>(
            inflate = { _, parent, _ -> PlazaBlockedUserBinding(PlazaBlockedUserView(parent.context)) },
            changePayload = { _, _ -> Unit },
        ) { cell ->
            cell.binding.root.bind(cell.item) { controller.unblock(it, feedViewModel) }
        },
    )

    override fun onListReady(listView: RecyclerView) {
        val ctx = requireContext()
        listView.itemAnimator = null
        listView.clipToPadding = false
        listView.setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), ctx.dp(24))
    }

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView =
        object : FeedSkeletonView(requireContext()) {
            // 和真条目同构：左边头像圆、两行文字、右边解除胶囊，行高 ~96dp。
            // 左右都要同时算上列表 padding(20dp) 和卡片 padding(16dp)，否则骨架的胶囊
            // 会顶到卡片外缘，加载完成时向左跳一下。
            override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
                val d = density
                val left = 36 * d
                val right = w - 36 * d
                val width = (right - left).coerceAtLeast(0f)
                if (width <= 0f) return
                val pill = minOf(96 * d, width * .4f)
                var y = 22 * d
                while (y < h) {
                    out.add(block(left, y + 14 * d, 44 * d, 44 * d, 22 * d))
                    out.add(block(left + 58 * d, y + 20 * d, (width - 58 * d - pill - 12 * d).coerceAtLeast(0f), 16 * d, 4 * d))
                    out.add(block(left + 58 * d, y + 44 * d, (width - 58 * d - pill - 60 * d).coerceAtLeast(0f), 12 * d, 4 * d))
                    out.add(block(right - pill, y + 22 * d, pill, 40 * d, 20 * d))
                    y += 96 * d
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        setupPlazaToolbar(view, getString(R.string.plaza_blocked_users))
        val content = view.findViewById<FrameLayout>(R.id.plaza_content)
        val column = view.findViewById<LinearLayout>(R.id.plaza_column)
        // 说明卡常驻在列表之上：屏蔽的作用范围是用户在这一页最需要知道的一件事。
        column.addView(
            ctx.noticeCard(R.drawable.ic_not_interested_black_24dp, getString(R.string.plaza_blocks_notice)),
            0,
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = ctx.dp(20)
                marginEnd = ctx.dp(20)
                topMargin = ctx.dp(16)
                bottomMargin = ctx.dp(4)
            },
        )
        content.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l, ctx.dp(720))
            if (column.layoutParams.width != width)
                column.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
        }
        feedBinding.feedStateText.apply {
            typeface = ctx.v3Font(400)
            textSize = 14f
            setTextColor(ctx.color(R.color.v3_text_2))
            lineHeightRatio(1.6f)
        }
        launchSuspend {
            feedViewModel.uiState.collect { state ->
                if (state.showFullscreenError) {
                    feedBinding.feedStateText.text = getString(R.string.plaza_blocks_state_message,
                        (state.refresh as LoadState.Error).throwable.plazaMessage().resolve(ctx),
                        getString(ceui.pixiv.feeds.R.string.feed_error_tap_retry))
                }
            }
        }
        launchSuspend {
            controller.error.collect { error ->
                if (error != null) {
                    controller.error.value = null
                    ctx.showPlazaError(error)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A page restored underneath an account switch must clear the old account's rows.
        try {
            controller.requireAccount()
        } catch (_: ceui.pixiv.plaza.PlazaFailure) {
            feedViewModel.mutateItems { emptyList() }
            feedViewModel.refresh()
        }
    }

    override fun onRefreshFailedWithContent(throwable: Throwable) {
        requireContext().showPlazaError(throwable.plazaMessage())
    }
}

internal class PlazaBlockedUserBinding(private val view: PlazaBlockedUserView) : ViewBinding {
    override fun getRoot(): PlazaBlockedUserView = view
}

/**
 * 一条屏蔽记录：首字母头像 → 用户名与 UID → 末端的解除胶囊。
 *
 * 接口没有头像字段，所以识别位用主题浅底的首字母承担，而不是留一块空白或摆一个通用人形。
 * 长用户名和放大字体下名字整列换行，胶囊保持 48dp 热区不被压扁。
 */
internal class PlazaBlockedUserView(ctx: Context) : LinearLayout(ctx) {
    private val monogram = MonogramView(ctx)
    private val name = ctx.label("", 16f, 600).apply { lineHeightRatio(1.4f) }
    private val identity = ctx.label("", 12f, 500, ctx.color(R.color.v3_text_2)).apply {
        lineHeightRatio(1.5f)
        fontFeatureSettings = "tnum"
    }
    private val button = ctx.compactPill(ctx.getString(R.string.plaza_unblock_action)) {}

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(10) }
        background = ctx.card(22)
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        addView(monogram, LayoutParams(ctx.dp(44), ctx.dp(44)))
        val text = LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(name, LayoutParams(-1, -2))
            addView(identity, LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        }
        addView(text, LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(14) })
        addView(button, LayoutParams(-2, -2).apply { marginStart = ctx.dp(12) })
    }

    /**
     * 放大字体 / 320dp 下，胶囊按原文宽度铺开会把用户名挤成一列单字。这里给它一个行宽上限，
     * 超了就让「解除屏蔽」自己换行，名字始终留得住一半以上的行宽。
     *
     * 只改 LayoutParams 的字段、不调用 setLayoutParams，所以量尺寸期间不会触发 requestLayout；
     * 判据只依赖行宽与胶囊的固有宽度，两者都不随这次调整变化，不会来回翻转。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val inner = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
        if (inner > 0) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            button.measure(unspecified, unspecified)
            val cap = (inner * .42f).toInt()
            (button.layoutParams as LayoutParams).width =
                if (button.measuredWidth > cap) cap else LayoutParams.WRAP_CONTENT
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun bind(item: PlazaBlockedUserItem, unblock: (Long) -> Unit) {
        val display = item.user.displayName.ifBlank { item.user.uid.toString() }
        name.text = display
        monogram.setLetter(display)
        identity.text = context.getString(R.string.plaza_blocked_uid, item.user.uid)
        button.contentDescription = context.getString(R.string.plaza_unblock_user, display)
        button.isEnabled = !item.busy
        button.alpha = if (item.busy) .5f else 1f
        button.setOnClickListener { unblock(item.user.uid) }
    }
}
