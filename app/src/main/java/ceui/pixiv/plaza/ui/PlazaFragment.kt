package ceui.pixiv.plaza.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ceui.lisa.R
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.panel.BottomPanelCoordinator
import ceui.pixiv.panel.PanelHost
import ceui.pixiv.panel.WindowSoftInputModeLease
import ceui.pixiv.panel.attachBottomPanel
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.session.SessionManager
import ceui.pixiv.sticker.InlineStickerPicker
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.widgets.applyV3RefreshTheme
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.theme.V3Palette

class PlazaFragment : PlazaTimelineFragment()

/**
 * Plaza feed and post detail on one V3 "content" recipe: standard app toolbar, edge-to-edge
 * hairline-separated post rows (as the novel and comment lists), a connected-segment filter in
 * the toolbar, an extended FAB for the one primary action, and explicit skeleton / empty /
 * error states instead of a status line.
 */
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
        val palette = V3Palette.from(ctx)
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
            installFilter(toolbar)
        }
        val frame = view.findViewById<FrameLayout>(R.id.plaza_content)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))

        // Content stage: list, first-screen skeleton and the state card share one slot so the
        // pull-to-refresh gesture works on all of them.
        val stage = FrameLayout(ctx)
        val refresh = SwipeRefreshLayout(ctx)
        val list =
            RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                clipToPadding = false
                setPadding(0, 0, 0, ctx.dp(if (postId > 0) 16 else 96))
                // Rows are edge-to-edge; only a full-bleed hairline separates them, drawn by
                // the decoration so prepends and appends never miss a line. No item animator:
                // like the download lists, updates snap instead of cross-fading or sliding.
                addItemDecoration(BottomDividerDecoration(ctx, R.drawable.hairline_divider))
                itemAnimator = null
            }
        recycler = list
        val skeleton = PlazaSkeletonView(ctx).apply { isVisible = false }
        // Feed empty / error state: centred in the content area, as the feeds framework does.
        val stateCard = PlazaStateView(ctx).apply { isVisible = false }
        stage.addView(list, FrameLayout.LayoutParams(-1, -1))
        stage.addView(skeleton, FrameLayout.LayoutParams(-1, -1))
        stage.addView(
            stateCard,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
                setMargins(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
            },
        )
        refresh.addView(stage)
        refresh.setOnChildScrollUpCallback { _, _ -> list.isVisible && list.canScrollVertically(-1) }
        column.addView(refresh, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh.applyV3RefreshTheme()
        refresh.setOnRefreshListener { model.refresh() }

        // Footer under the list: paging spinner, or a retry pill when a later page fails.
        val footer =
            LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
                isVisible = false
            }
        val footerProgress =
            ProgressBar(ctx).apply {
                indeterminateTintList = ColorStateList.valueOf(palette.primary)
                isVisible = false
            }
        val footerRetry =
            ctx.pillButton(ctx.getString(R.string.plaza_retry), primary = false) { model.refresh() }
                .apply { isVisible = false }
        footer.addView(footerProgress, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
        footer.addView(footerRetry, LinearLayout.LayoutParams(-2, -2))
        column.addView(footer, LinearLayout.LayoutParams(-1, -2))

        val adapter =
            PostAdapter(
                model::like,
                ::confirmDelete,
                ::preview,
                postId,
                model::react,
                onReply = if (postId > 0) ::startReply else null,
                onBind = model::ensureFreshImages,
            )
        list.adapter = adapter
        val fab = if (postId == 0L) installComposeFab(frame, list) else null
        // Keep text and media readable on wide panes without changing toolbar geometry.
        frame.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l, ctx.dp(720))
            if (column.layoutParams.width != width)
                column.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
            fab?.let {
                val margin = (r - l - width) / 2 + ctx.dp(16)
                val lp = it.layoutParams as FrameLayout.LayoutParams
                if (lp.marginEnd != margin) {
                    lp.marginEnd = margin
                    it.layoutParams = lp
                }
            }
        }
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
                val firstLoad =
                    posts.isEmpty() && state.error == null && (state.loading || state.restoringCache)
                val emptyError = posts.isEmpty() && state.error != null
                skeleton.isVisible = firstLoad
                list.isVisible = posts.isNotEmpty()
                when {
                    firstLoad -> stateCard.hide()
                    emptyError ->
                        stateCard.show(
                            ceui.pixiv.feeds.R.drawable.ic_feed_error,
                            ctx.getString(R.string.plaza_load_error_title) + "\n" +
                                state.error?.resolve(ctx).orEmpty(),
                            ctx.getString(R.string.plaza_retry),
                        ) { model.refresh() }
                    posts.isEmpty() && !state.loading ->
                        when {
                            postId > 0 ->
                                stateCard.show(
                                    R.mipmap.empty_img,
                                    ctx.getString(R.string.plaza_not_found),
                                )
                            model.mine ->
                                stateCard.show(
                                    R.mipmap.empty_img,
                                    ctx.getString(R.string.plaza_mine_empty) + "\n" +
                                        ctx.getString(R.string.plaza_mine_empty_desc),
                                    ctx.getString(R.string.plaza_compose_title),
                                ) { ctx.openComposer() }
                            else ->
                                stateCard.show(
                                    R.mipmap.empty_img,
                                    ctx.getString(R.string.plaza_empty_title) + "\n" +
                                        ctx.getString(R.string.plaza_empty_desc),
                                    ctx.getString(R.string.plaza_compose_title),
                                ) { ctx.openComposer() }
                        }
                    else -> stateCard.hide()
                }
                // A post whose comments have finished loading and came back empty shows the
                // same empty state under "Comments (0)", inside the list so it scrolls with it.
                adapter.emptyComments =
                    postId > 0 && state.parent != null && state.items.isEmpty() &&
                        !state.loading && !state.loadingMore && state.error == null
                // Comments of a cached post, or the next page, load below the visible content.
                footerProgress.isVisible =
                    posts.isNotEmpty() && (state.loadingMore || (state.loading && state.items.isEmpty()))
                footerRetry.isVisible =
                    posts.isNotEmpty() && state.error != null && !state.loading && !state.loadingMore
                footer.isVisible = footerProgress.isVisible || footerRetry.isVisible
                renderReplyComposer()
            }
        }
    }

    /** MD3-E connected segments on the coloured toolbar, as in the bookmark library. */
    private fun installFilter(toolbar: Toolbar) {
        val ctx = requireContext()
        val title = toolbar.findViewById<TextView>(R.id.toolbar_title)
        title.isVisible = false
        val track =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_toolbar_segment_track)
                setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            }
        val options = mutableListOf<Pair<TextView, Boolean>>()
        fun sync() = options.forEach { (option, mine) -> option.isSelected = mine == model.mine }
        listOf(R.string.plaza_filter_all to false, R.string.plaza_filter_mine to true).forEach { (res, mine) ->
            val option =
                ctx.label(ctx.getString(res), 13f, 600).apply {
                    setTextColor(ContextCompat.getColorStateList(ctx, R.color.toolbar_segment_text))
                    setBackgroundResource(R.drawable.bg_toolbar_segment_option)
                    gravity = Gravity.CENTER
                    minWidth = ctx.dp(88)
                    minHeight = ctx.dp(36)
                    setPadding(ctx.dp(16), ctx.dp(7), ctx.dp(16), ctx.dp(7))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        model.selectMine(mine)
                        sync()
                        recycler?.scrollToPosition(0)
                    }
                }
            options += option to mine
            track.addView(option, LinearLayout.LayoutParams(-2, -2))
        }
        sync()
        toolbar.addView(track, Toolbar.LayoutParams(-2, -2, Gravity.CENTER))
    }

    /** Extended FAB: the feed's single strongest action, retreating while the user reads. */
    private fun installComposeFab(frame: FrameLayout, list: RecyclerView): View {
        val ctx = requireContext()
        val fab =
            ctx.pillButton(
                ctx.getString(R.string.plaza_compose_title),
                primary = true,
                icon = R.drawable.ic_add_black_24dp,
            ) { ctx.openComposer() }
                .apply {
                    minHeight = ctx.dp(52)
                    setPadding(ctx.dp(20), 0, ctx.dp(24), 0)
                    elevation = ctx.dpF(4f)
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                }
        frame.addView(
            fab,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            },
        )
        var shown = true
        fun setShown(value: Boolean) {
            if (shown == value) return
            shown = value
            fab.animate().cancel()
            if (!motionEnabled()) {
                fab.isVisible = value
                fab.scaleX = 1f
                fab.scaleY = 1f
                fab.alpha = 1f
                return
            }
            if (value) fab.isVisible = true
            fab.animate()
                .scaleX(if (value) 1f else .8f)
                .scaleY(if (value) 1f else .8f)
                .alpha(if (value) 1f else 0f)
                .setDuration(200)
                .withEndAction { if (!value) fab.isVisible = false }
                .start()
        }
        list.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy > ctx.dp(8)) setShown(false)
                    else if (dy < -ctx.dp(8) || !rv.canScrollVertically(-1)) setShown(true)
                }
            }
        )
        return fab
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
