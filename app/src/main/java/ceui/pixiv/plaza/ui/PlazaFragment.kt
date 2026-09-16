package ceui.pixiv.plaza.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.plaza.PlazaPostCacheEntry
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.theme.V3Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Plaza feed on the feeds framework. The framework owns paging, pull-to-refresh, the disk
 * first page on cold start, the skeleton, empty / error states and network-restored retries;
 * [PlazaFeedController] owns the all / mine scope, the resume policy, mutations and their busy
 * marker. The page keeps the same shell as before the migration: the standard app toolbar with
 * the connected all / mine segments, edge-to-edge hairline rows, the extended "post" FAB, and
 * plaza's own error copy and alert dialogs.
 */
class PlazaFragment : FeedFragment(R.layout.fragment_plaza_feed) {
    private val controller: PlazaFeedController by viewModels()

    // The source captures only the controller ViewModel, never this Fragment or its views.
    override val feedViewModel by feedViewModels { controller.source }

    private var imageViewerOpen = false
    private val imageViewer =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            imageViewerOpen = false
        }

    /** True between the user's pull and the end of that refresh; only then does the ring spin. */
    private var userPulled = false

    /** True while the list restarts for another scope (all / mine / account); shows the skeleton. */
    private var switchingScope = false
    private var alertedAppendError: LoadState.Error? = null
    private val observers = mutableMapOf<Long, Observer<PlazaPostCacheEntry>>()

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out androidx.viewbinding.ViewBinding>> =
        listOf(
            feedRenderer<PlazaPostItem, PostViewBinding>(
                inflate = { _, parent, _ -> PostViewBinding(PostView(parent.context)) },
                recycle = { it.binding.root.clear() },
                // Rebind in place; a non-null payload also keeps any change cross-fade away.
                changePayload = { _, _ -> Unit },
            ) { cell ->
                val item = cell.item
                controller.ensureFreshImages(item.post)
                cell.binding.root.bind(
                    item.post,
                    item.busy,
                    false,
                    controller::like,
                    ::confirmDelete,
                    ::preview,
                    controller::react,
                )
            }
        )

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager =
        LinearLayoutManager(requireContext())

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView =
        PlazaSkeletonView(requireContext())

    override fun onListReady(listView: RecyclerView) {
        val ctx = requireContext()
        // Rows are edge-to-edge; only a full-bleed hairline separates them, drawn by the
        // decoration so prepends and appends never miss a line. No item animator: like the
        // download lists, updates snap instead of cross-fading or sliding.
        listView.addItemDecoration(BottomDividerDecoration(ctx, R.drawable.hairline_divider))
        listView.itemAnimator = null
        listView.clipToPadding = false
        listView.setPadding(0, 0, 0, ctx.dp(96))
        listView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    override val emptyStateText: CharSequence
        get() =
            if (controller.mine)
                getString(R.string.plaza_mine_empty) + "\n" + getString(R.string.plaza_mine_empty_desc)
            else getString(R.string.plaza_empty_title) + "\n" + getString(R.string.plaza_empty_desc)

    override val emptyStateAction: Pair<CharSequence, () -> Unit>
        get() = getString(R.string.plaza_compose_title) to { requireContext().openComposer() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val toolbar = setupPlazaToolbar(view, ctx.getString(R.string.plaza_title))
        installFilter(toolbar)
        styleStateViews()
        // Every refresh entry point goes through the controller so it waits for a mutation.
        feedBinding.feedRefreshLayout.setOnRefreshListener {
            userPulled = true
            refreshNow()
        }
        feedBinding.feedStateText.setOnClickListener { refreshNow() }

        val content = view.findViewById<FrameLayout>(R.id.plaza_content)
        val column = view.findViewById<View>(R.id.plaza_column)
        val fab = installComposeFab(content, feedBinding.feedListView)
        // Keep text and media readable on wide panes without changing toolbar geometry.
        content.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val width = minOf(r - l, ctx.dp(720))
            if (column.layoutParams.width != width) {
                column.layoutParams =
                    FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
            }
            val margin = (r - l - width) / 2 + ctx.dp(16)
            val lp = fab.layoutParams as FrameLayout.LayoutParams
            if (lp.marginEnd != margin) {
                lp.marginEnd = margin
                fab.layoutParams = lp
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Subscribes after FeedFragment's own collector, so every emission reaches
                // syncPlazaState once the framework has finished painting that same state.
                launch { feedViewModel.uiState.collect(::syncPlazaState) }
                launch { controller.refreshRequests.collect { feedViewModel.refresh() } }
                launch {
                    controller.busyIds.collect { busy ->
                        feedViewModel.updateItems<PlazaPostItem> { item ->
                            val b = item.post.id in busy
                            if (item.busy == b) item else item.copy(busy = b)
                        }
                    }
                }
                launch {
                    controller.error.collect {
                        controller.takeErrorForAlert()?.let(ctx::showPlazaError)
                    }
                }
            }
        }
    }

    /** Runs a refresh now unless a mutation is in flight; then the controller replays it. */
    private fun refreshNow() {
        if (controller.requestRefresh()) feedViewModel.refresh()
    }

    /**
     * Restart the list for another scope: the old rows leave at once, the scope's disk
     * snapshot shows while the network first page loads, exactly as before the migration.
     *
     * Deliberately not routed through [refreshNow]: a scope restart must not be deferred behind
     * an in-flight mutation, because the list has already been emptied and a deferred refresh
     * would leave the page sitting on the "no posts yet" empty state until that mutation
     * finishes. A stale page still cannot overwrite the mutation — [PlazaFeedSource] refetches
     * any page whose revision moved while it was in flight.
     */
    private fun restartScope() {
        val vm = feedViewModel
        switchingScope = true
        vm.refresh()
        vm.adoptCursorAndMutateItems(null) { emptyList() }
        viewLifecycleOwner.lifecycleScope.launch {
            val restored =
                try {
                    controller.source.loadFromCache()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: return@launch
            val state = vm.uiState.value
            // Never let a late snapshot replace a network page that already arrived.
            if (state.items.isEmpty() && (switchingScope || state.refresh is LoadState.Error)) {
                vm.adoptCursorAndMutateItems(restored.nextCursor) { restored.items }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        when (controller.enter()) {
            PlazaFeedController.Entry.SWITCH_SCOPE -> restartScope()
            PlazaFeedController.Entry.REFRESH -> refreshNow()
            PlazaFeedController.Entry.NONE -> Unit
        }
    }

    override fun onNetworkRestored() {
        val state = feedViewModel.uiState.value
        when {
            state.showFullscreenError -> refreshNow()
            state.append is LoadState.Error -> feedViewModel.retryAppend()
        }
    }

    override fun onRefreshFailedWithContent(throwable: Throwable) {
        requireContext().showPlazaError(throwable.plazaMessage())
    }

    /**
     * The ring belongs to the user's pull gesture. Cold-start cache refreshes, resume refreshes,
     * network-restored retries and scope switches load silently, as they did before the
     * migration (docs/plaza-design-review.md, 2026-09-16).
     */
    override fun shouldShowRefreshSpinner(state: FeedUiState): Boolean =
        userPulled && super.shouldShowRefreshSpinner(state)

    /**
     * The page's last word on each state, applied after the framework's `render` has painted it.
     *
     * This deliberately does not hang off `onListCommitted`: that callback rides
     * `AsyncListDiffer.submitList`, which runs synchronously — before `render` touches any view —
     * whenever the submitted list is the same instance as the current one. A plain refresh is
     * exactly that case (only `refresh` flips to Loading, `items` keeps its instance), so
     * corrections made there would be overwritten in the same pass, and the callback can also
     * arrive after `onDestroyView` for an async diff.
     */
    private fun syncPlazaState(state: FeedUiState) {
        val ctx = requireContext()
        val binding = feedBinding
        if (state.refresh !is LoadState.Loading) {
            userPulled = false
            switchingScope = false
        }
        // A scope restart empties the list on purpose. Keep the first-screen skeleton instead of
        // the framework's empty state, which would claim this account has no posts.
        if (switchingScope && state.items.isEmpty()) {
            binding.feedSkeleton.isVisible = true
            binding.feedStateContainer.isVisible = false
        }
        // Plaza's own status-code copy: the host's shared mapping falls back to printing the
        // Tokyo API's raw error body when it carries no user_message.
        if (state.showFullscreenError) {
            binding.feedStateText.text =
                (state.refresh as LoadState.Error).throwable.plazaMessage().resolve(ctx) + "\n" +
                    getString(ceui.pixiv.feeds.R.string.feed_error_tap_retry)
        }
        val appendError = state.append as? LoadState.Error
        if (appendError != null && appendError !== alertedAppendError) {
            alertedAppendError = appendError
            ctx.showPlazaError(appendError.throwable.plazaMessage())
        }
        syncObservers(state.items)
    }

    /** Observe the same ObjectPool entries as the detail page; edits land in the feed rows. */
    private fun syncObservers(items: List<FeedItem>) {
        val ids = items.mapNotNullTo(HashSet()) { (it as? PlazaPostItem)?.post?.id }
        (observers.keys - ids).forEach { id ->
            observers.remove(id)?.let { ObjectPool.get<PlazaPostCacheEntry>(id).removeObserver(it) }
        }
        (ids - observers.keys).forEach { id ->
            val observer = Observer<PlazaPostCacheEntry> { entry -> onPooledEntry(id, entry) }
            observers[id] = observer
            ObjectPool.get<PlazaPostCacheEntry>(id).observe(viewLifecycleOwner, observer)
        }
    }

    private fun onPooledEntry(id: Long, entry: PlazaPostCacheEntry) {
        val uid = controller.account
        if (entry.viewerUid != uid || uid != SessionManager.loggedInUid) return
        feedViewModel.mutateItems { list ->
            val index = list.indexOfFirst { it is PlazaPostItem && it.post.id == id }
            if (index < 0) return@mutateItems list
            val item = list[index] as PlazaPostItem
            val post = entry.post
            when {
                post == null -> list.filterIndexed { i, _ -> i != index }
                item.post === post -> list
                else -> ArrayList(list).also { it[index] = item.copy(post = post) }
            }
        }
    }

    /** The framework's state views in plaza's V3 type and pill, as the pages looked before. */
    private fun styleStateViews() {
        val ctx = requireContext()
        val p = V3Palette.from(ctx)
        feedBinding.feedStateText.apply {
            typeface = ctx.v3Font(400)
            textSize = 14f
            setTextColor(ctx.color(R.color.v3_text_2))
            lineHeightRatio(1.6f)
        }
        feedBinding.feedStateAction.apply {
            typeface = ctx.v3Font(600)
            textSize = 14f
            setTextColor(p.onPrimary)
            background = ctx.ripple(p.pillPrimary(999f), shape(999f, Color.WHITE))
            minHeight = ctx.dp(48)
            setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(10))
            pressScale()
        }
    }

    /** MD3-E connected segments on the coloured toolbar, as in the bookmark library. */
    private fun installFilter(toolbar: Toolbar) {
        val ctx = requireContext()
        toolbar.findViewById<TextView>(R.id.toolbar_title).isVisible = false
        val track =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_toolbar_segment_track)
                setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            }
        val options = mutableListOf<Pair<TextView, Boolean>>()
        fun sync() = options.forEach { (option, mine) -> option.isSelected = mine == controller.mine }
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
                        if (!controller.selectMine(mine)) return@setOnClickListener
                        sync()
                        feedBinding.feedListView.scrollToPosition(0)
                        restartScope()
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
                    outlineProvider = ViewOutlineProvider.BACKGROUND
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

    override fun onDestroyView() {
        // Lifecycle-bound observers are already detached; only the bookkeeping remains.
        observers.clear()
        super.onDestroyView()
    }

    private fun confirmDelete(post: PlazaPost) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setMessage(getString(R.string.plaza_delete_confirm))
            .addAction(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .addAction(0, R.string.plaza_delete_confirm_yes, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                controller.delete(post)
            }
            .show()
    }

    private fun preview(post: PlazaPost, index: Int, thumbnail: View) {
        if (imageViewerOpen || index !in post.images.indices) return
        imageViewerOpen = true
        imageViewer.launch(PlazaImageViewer.intent(requireContext(), post, index, thumbnail))
    }
}

/** feeds hands over a Throwable; plaza's mapping wants the Exception it usually is. */
internal fun Throwable.plazaMessage() = plazaError(this as? Exception ?: Exception(this))
