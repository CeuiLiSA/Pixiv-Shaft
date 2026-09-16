package ceui.pixiv.plaza.ui

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import ceui.pixiv.panel.BottomPanelCoordinator
import ceui.pixiv.panel.PanelHost
import ceui.pixiv.panel.WindowSoftInputModeLease
import ceui.pixiv.panel.attachBottomPanel
import ceui.pixiv.session.SessionManager
import ceui.pixiv.sticker.InlineStickerPicker
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
import ceui.pixiv.witstudio.dialog.WitDialogAction

class PlazaFragment : PlazaTimelineFragment()

open class PlazaTimelineFragment : Fragment(R.layout.fragment_plaza_shell) {
    private val model: PlazaTimelineViewModel by viewModels()
    private val replyModel: PlazaComposeViewModel by viewModels()
    private var replyBar: PlazaReplyBar? = null
    private var replyPanel: BottomPanelCoordinator? = null
    private var softInputLease: WindowSoftInputModeLease.Handle? = null
    private var recycler: RecyclerView? = null
    private var imageViewerOpen = false
    private val imageViewer =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {
            imageViewerOpen = false
        }
    protected open val postId
        get() = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val toolbar =
            setupPlazaToolbar(
                view,
                if (postId > 0) ctx.getString(R.string.plaza_post_detail_title)
                else ctx.getString(R.string.plaza_title),
                bottomPanel = postId > 0,
            )
        if (postId > 0) {
            toolbar.menu.add(R.string.plaza_more_menu)
                .setIcon(R.drawable.ic_more_vert_black_24dp)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            toolbar.setOnMenuItemClickListener {
                model.state.value.parent?.let { ctx.showPostMenu(it, ::confirmDelete) }
                true
            }
        } else {
            toolbar.menu.add(R.string.plaza_send_post)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            toolbar.setOnMenuItemClickListener {
                ctx.openComposer()
                true
            }
        }
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
        val adapter = PostAdapter(
            model::like, ::confirmDelete, ::preview, postId, model::react,
            onReply = if (postId > 0) ::startReply else null,
            onBind = model::ensureFreshImages,
        )
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
        if (postId > 0) {
            val bar = PlazaReplyBar(ctx)
            replyBar = bar
            column.addView(bar, LinearLayout.LayoutParams(-1, -2))
            setupReplyComposer(bar, column, list)
        }
        launchSuspend {
            model.state.collect { state ->
                model.takeErrorForAlert()?.let(ctx::showPlazaError)
                val posts = listOfNotNull(state.parent) + state.items
                adapter.busy = state.busyIds
                adapter.submitList(posts)
                // SwipeRefreshLayout starts its spinner for the user's pull gesture.
                // Loading cached content or comments must never start that animation.
                if (!state.loading) refresh.isRefreshing = false
                status.text =
                    when {
                        state.restoringCache -> ""
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
                renderReplyComposer()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.enter(postId)
        if (postId > 0 && softInputLease == null) {
            softInputLease = WindowSoftInputModeLease.acquireAdjustResize(requireActivity().window)
        }
    }

    override fun onPause() {
        softInputLease?.release()
        softInputLease = null
        super.onPause()
    }

    override fun onDestroyView() {
        softInputLease?.release()
        softInputLease = null
        replyBar?.emojiPanel?.onPanelVisibilityChanged = null
        replyBar = null
        replyPanel = null
        recycler?.adapter = null
        recycler = null
        super.onDestroyView()
    }

    private fun setupReplyComposer(bar: PlazaReplyBar, root: View, list: RecyclerView) {
        val input = bar.composer
        if (replyModel.replyTo == null) replyModel.replyTo = postId
        replyModel.avatarUrl = SessionManager.loggedInUser?.profile_image_urls?.medium
        input.etInput.setText(replyModel.text)
        input.etInput.setSelection(replyModel.text.length)
        input.etInput.doAfterTextChanged {
            replyModel.text = it?.toString().orEmpty()
            renderReplyComposer()
        }
        input.btnSend.setOnClickListener {
            if (model.state.value.parent != null) {
                replyModel.send(requireContext().contentResolver)
                renderReplyComposer()
            }
        }
        input.btnReplyBarClose.setOnClickListener {
            if (!replyModel.state.value.sending) {
                replyModel.replyTo = postId
                renderReplyComposer()
            }
        }
        // Keep the existing post-reaction semantics while sharing chat's inline picker.
        val stickers = InlineStickerPicker(requireContext(), bar.emojiPanel, viewLifecycleOwner) { sticker ->
            if (!replyModel.state.value.sending) {
                model.state.value.parent?.let { model.react(it, "sticker:${sticker.stickerId}") }
            }
        }
        bar.emojiPanel.onPanelVisibilityChanged = stickers::setActive
        replyPanel = attachBottomPanel(object : PanelHost {
            override val panelRoot get() = root
            override val panelView get() = bar.emojiPanel
            override val panelComposerView get() = input.root
            override val panelInputView get() = input.etInput
            override val panelContentView get() = list
            override val panelToggleButton get() = input.btnEmoji
            override val panelToggleIconRes get() = R.drawable.chat_ic_emoji
            override val keyboardToggleIconRes get() = R.drawable.chat_ic_keyboard
        })
        renderReplyComposer()
        launchSuspend {
            replyModel.state.collect {
                replyModel.takeErrorForAlert()?.let(requireContext()::showPlazaError)
                if (replyModel.consumeSentReply() != null) {
                    input.etInput.text?.clear()
                    replyModel.replyTo = postId
                    model.refresh()
                }
                renderReplyComposer()
            }
        }
    }

    private fun startReply(post: PlazaPost) {
        if (replyBar == null || replyModel.state.value.sending) return
        replyModel.replyTo = post.id
        replyModel.replyName = post.displayName
        replyModel.replyPreview = post.text.ifBlank { post.title }
        renderReplyComposer()
        replyPanel?.switchToKeyboard()
    }

    private fun renderReplyComposer() {
        val input = replyBar?.composer ?: return
        val sending = replyModel.state.value.sending
        val available = model.state.value.parent != null
        input.etInput.isEnabled = available && !sending
        input.btnSend.isEnabled = available && replyModel.canSend()
        input.btnEmoji.isEnabled = available && !sending
        input.btnReplyBarClose.isEnabled = !sending
        input.replyBar.isVisible = replyModel.replyTo != null && replyModel.replyTo != postId
        input.tvReplyBarName.text = getString(R.string.chat_reply_bar_title, replyModel.replyName)
        input.tvReplyBarText.text = replyModel.replyPreview.replace('\n', ' ')
    }

    private fun confirmDelete(post: PlazaPost) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setMessage(getString(R.string.plaza_delete_confirm))
            .addAction(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .addAction(0, R.string.plaza_delete_confirm_yes, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                model.delete(post)
            }
            .show()
    }

    private fun preview(post: PlazaPost, index: Int, thumbnail: View) {
        if (imageViewerOpen || index !in post.images.indices) return
        imageViewerOpen = true
        imageViewer.launch(PlazaImageViewer.intent(requireContext(), post, index, thumbnail))
    }
}
