package ceui.pixiv.plaza.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ceui.lisa.R
import ceui.lisa.fragments.BaseFragment
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.widgets.applyV3RefreshTheme
import kotlinx.coroutines.launch

class PlazaFragment : PlazaTimelineFragment()

open class PlazaTimelineFragment : Fragment(R.layout.fragment_plaza_shell) {
    private val model: PlazaTimelineViewModel by viewModels()
    private var recycler: RecyclerView? = null
    protected open val postId get() = 0L
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val header=setupPlazaHeader(view,if(postId>0) "帖子详情" else "广场")
        header.action.text="发帖"
        header.trailing.setOnClickListener { ctx.openComposer() }
        val frame = view.findViewById<FrameLayout>(R.id.plaza_content)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        // Keep text and media readable on wide panes without changing toolbar geometry.
        frame.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l, ctx.dp(720))
            if (column.layoutParams.width != width) column.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
        }
        val status = ctx.label("").apply { gravity = Gravity.CENTER; setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12)) }
        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        status.setOnClickListener { model.refresh() }
        val refresh = SwipeRefreshLayout(ctx)
        val list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); clipToPadding = false; setPadding(0,0,0,ctx.dp(16)) }
        recycler = list
        refresh.addView(list); column.addView(refresh, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh.applyV3RefreshTheme(); refresh.setOnRefreshListener { model.refresh() }
        val adapter = PostAdapter(model::like, ::confirmDelete, ::preview, postId, model::react)
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy >= 0 && (rv.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() >= adapter.itemCount - 4) model.more()
            }
        })
        var replyBar:PlazaReplyBar?=null
        if(postId>0) {
            replyBar=PlazaReplyBar(ctx,reply={ctx.openComposer(postId)},react={
                model.state.value.parent?.let {post->
                    val emoji=arrayOf("👀","💪","👌","😂","🤔")
                    WitDialog.MenuDialogBuilder(ctx).addItems(emoji) {d,i->d.dismiss();model.react(post,emoji[i])}.show()
                }
            },comments={
                if(adapter.itemCount>1) list.smoothScrollToPosition(1) else ctx.openComposer(postId)
            })
            column.addView(replyBar)
            header.trailing.removeAllViews()
            header.trailing.addView(ctx.figmaIcon(R.drawable.ic_plaza_figma_more,"更多操作",true).apply {isClickable=false;isFocusable=false})
            header.trailing.setOnClickListener { model.state.value.parent?.let {ctx.showPostMenu(it,::confirmDelete)} }
        }
        launchSuspend {
            model.state.collect { state ->
                val posts = listOfNotNull(state.parent) + state.items
                adapter.busy = state.busyIds
                adapter.submitList(posts)
                refresh.isRefreshing = state.loading
                status.text = when {
                    state.error != null -> state.error + " · 点按重试"
                    state.loading && posts.isEmpty() -> "正在加载…"
                    posts.isEmpty() -> if (postId > 0) "帖子已删除或不存在" else if (model.mine) "还没有发布帖子" else "还没有帖子，分享你的第一条动态"
                    state.loadingMore -> "正在加载更多…"
                    else -> ""
                }
                status.isVisible = status.text.isNotEmpty()
                replyBar?.bind(state.parent,postId in state.busyIds)
            }
        }
    }
    override fun onResume() { super.onResume(); model.enter(postId) }
    override fun onDestroyView() { recycler?.adapter = null; recycler = null; super.onDestroyView() }
    private fun confirmDelete(post: PlazaPost) {
        WitDialog.MessageDialogBuilder(requireContext()).setMessage("删除这条帖子？")
            .addAction("取消") { d, _ -> d.dismiss() }
            .addAction("删除") { d, _ -> d.dismiss(); model.delete(post) }.show()
    }
    private fun preview(post: PlazaPost, index: Int) {
        if (childFragmentManager.findFragmentByTag("images") != null) return
        PlazaImageViewer.newInstance(post, index).show(childFragmentManager, "images")
    }
}
