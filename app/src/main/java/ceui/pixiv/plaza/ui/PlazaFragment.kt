package ceui.pixiv.plaza.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ceui.lisa.R
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.widgets.applyV3RefreshTheme
import ceui.pixiv.witstudio.dialog.WitDialog

class PlazaFragment : PlazaTimelineFragment()

open class PlazaTimelineFragment : Fragment(R.layout.fragment_plaza_shell) {
    private val model: PlazaTimelineViewModel by viewModels()
    private var recycler: RecyclerView? = null
    protected open val postId
        get() = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val header =
            setupPlazaHeader(
                view,
                if (postId > 0) ctx.getString(R.string.plaza_post_detail_title)
                else ctx.getString(R.string.plaza_title),
            )
        header.action.text = ctx.getString(R.string.plaza_send_post)
        header.trailing.setOnClickListener { ctx.openComposer() }
        val frame = view.findViewById<FrameLayout>(R.id.plaza_content)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        // Keep text and media readable on wide panes without changing toolbar geometry.
        frame.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l, ctx.dp(720))
            if (column.layoutParams.width != width)
                column.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
        }
        val status =
            ctx.label("").apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            }
        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        status.setOnClickListener { model.refresh() }
        val refresh = SwipeRefreshLayout(ctx)
        val list =
            RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                clipToPadding = false
                setPadding(0, 0, 0, ctx.dp(16))
            }
        recycler = list
        refresh.addView(list)
        column.addView(refresh, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh.applyV3RefreshTheme()
        refresh.setOnRefreshListener { model.refresh() }
        val adapter = PostAdapter(model::like, ::confirmDelete, ::preview, postId, model::react)
        list.adapter = adapter
        list.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (
                        dy >= 0 &&
                            (rv.layoutManager as LinearLayoutManager)
                                .findLastVisibleItemPosition() >= adapter.itemCount - 4
                    )
                        model.more()
                }
            }
        )
        var replyBar: PlazaReplyBar? = null
        if (postId > 0) {
            replyBar =
                PlazaReplyBar(
                    ctx,
                    reply = { ctx.openComposer(postId) },
                    react = {
                        model.state.value.parent?.let { post ->
                            val emoji = arrayOf("👀", "💪", "👌", "😂", "🤔")
                            WitDialog.MenuDialogBuilder(ctx)
                                .addItems(emoji) { d, i ->
                                    d.dismiss()
                                    model.react(post, emoji[i])
                                }
                                .show()
                        }
                    },
                    comments = {
                        if (adapter.itemCount > 1) list.smoothScrollToPosition(1)
                        else ctx.openComposer(postId)
                    },
                )
            column.addView(replyBar)
            header.trailing.removeAllViews()
            header.trailing.addView(
                ctx.figmaIcon(
                        R.drawable.ic_plaza_figma_more,
                        ctx.getString(R.string.plaza_more_menu),
                        true,
                    )
                    .apply {
                        isClickable = false
                        isFocusable = false
                    }
            )
            header.trailing.setOnClickListener {
                model.state.value.parent?.let { ctx.showPostMenu(it, ::confirmDelete) }
            }
        }
        launchSuspend {
            model.state.collect { state ->
                model.takeErrorForAlert()?.let(ctx::showPlazaError)
                val posts = listOfNotNull(state.parent) + state.items
                adapter.busy = state.busyIds
                adapter.submitList(posts)
                refresh.isRefreshing = state.loading
                status.text =
                    when {
                        state.error != null ->
                            ctx.getString(R.string.plaza_retry_message, state.error.resolve(ctx))
                        state.loading && posts.isEmpty() -> ctx.getString(R.string.plaza_loading)
                        posts.isEmpty() ->
                            if (postId > 0) ctx.getString(R.string.plaza_not_found)
                            else if (model.mine) ctx.getString(R.string.plaza_mine_empty)
                            else ctx.getString(R.string.plaza_feed_empty)
                        state.loadingMore -> ctx.getString(R.string.plaza_loading_more)
                        else -> ""
                    }
                status.isVisible = status.text.isNotEmpty()
                replyBar?.bind(state.parent, postId in state.busyIds)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.enter(postId)
    }

    override fun onDestroyView() {
        recycler?.adapter = null
        recycler = null
        super.onDestroyView()
    }

    private fun confirmDelete(post: PlazaPost) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setMessage(getString(R.string.plaza_delete_confirm))
            .addAction(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .addAction(getString(R.string.plaza_delete_confirm_yes)) { d, _ ->
                d.dismiss()
                model.delete(post)
            }
            .show()
    }

    private fun preview(post: PlazaPost, index: Int) {
        if (childFragmentManager.findFragmentByTag("images") != null) return
        PlazaImageViewer.newInstance(post, index).show(childFragmentManager, "images")
    }
}
