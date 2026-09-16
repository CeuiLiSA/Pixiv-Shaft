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
import ceui.pixiv.witstudio.theme.V3Palette

/** Full-page blocked-user management on the same feeds framework as the plaza. */
class PlazaBlockedUsersFragment : FeedFragment(R.layout.fragment_plaza_feed) {
    private val controller: PlazaBlocksController by viewModels()
    override val feedViewModel by feedViewModels { controller }

    override val emptyStateText: CharSequence
        get() = getString(R.string.plaza_blocks_state_message, getString(R.string.plaza_blocks_empty), getString(R.string.plaza_blocks_notice))

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
        listView.setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20))
    }

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView =
        object : FeedSkeletonView(requireContext()) {
            override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
                val d = density
                val width = (w - 76 * d).coerceAtLeast(0f)
                if (width == 0f) return
                var y = 38 * d
                while (y < h) {
                    out.add(block(38 * d, y, width * .65f, 18 * d, 4 * d))
                    out.add(block(38 * d, y + 28 * d, width * .4f, 12 * d, 4 * d))
                    out.add(block(38 * d, y + 60 * d, width, 48 * d, 24 * d))
                    y += 156 * d
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        setupPlazaToolbar(view, getString(R.string.plaza_blocked_users))
        val content = view.findViewById<FrameLayout>(R.id.plaza_content)
        val column = view.findViewById<View>(R.id.plaza_column)
        content.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l, ctx.dp(720))
            if (column.layoutParams.width != width)
                column.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
        }
        feedBinding.feedStateText.apply {
            typeface = ctx.v3Font(400)
            textSize = 14f
            setTextColor(V3Palette.from(ctx).textSecondary)
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

internal class PlazaBlockedUserView(ctx: Context) : LinearLayout(ctx) {
    private val name = ctx.label("", 16f, 600).apply { lineHeightRatio(1.5f) }
    private val identity = ctx.label("", 12f, 500, V3Palette.from(ctx).textSecondary).apply { lineHeightRatio(1.6f) }
    private val button = ctx.pillButton(ctx.getString(R.string.plaza_unblock_action), false) {}

    init {
        orientation = VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(12) }
        background = ctx.card(22)
        setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
        addView(name, LayoutParams(-1, -2))
        addView(identity, LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
        addView(button, LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) })
    }

    fun bind(item: PlazaBlockedUserItem, unblock: (Long) -> Unit) {
        name.text = item.user.displayName.ifBlank { item.user.uid.toString() }
        identity.text = context.getString(R.string.plaza_blocked_uid, item.user.uid)
        button.contentDescription = context.getString(R.string.plaza_unblock_user, name.text)
        button.isEnabled = !item.busy
        button.alpha = if (item.busy) .5f else 1f
        button.setOnClickListener { unblock(item.user.uid) }
    }
}
