package ceui.pixiv.ui.artlist

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.library.LibraryFilterViews
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.v3Font
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ArtlistsFragment : FeedFragment(R.layout.fragment_artlists) {
    private val model: ArtlistModel by viewModels()
    override val feedViewModel by feedViewModels<String>(autoLoad = false) { model.lists }
    private var filters: LibraryFilterViews.ChoiceGroup? = null
    private val scopes = listOf("all", "mine", "saved")

    override val emptyStateText: CharSequence get() = getString(
        if (model.scope == "saved") R.string.artlist_saved_empty else R.string.artlist_empty)
    override val emptyStateAction: Pair<CharSequence, () -> Unit> get() =
        if (model.scope == "saved") getString(R.string.artlist_explore) to { selectScope("all") }
        else getString(R.string.artlist_create) to { create() }

    override fun onCreateRenderers(): List<ceui.pixiv.feeds.FeedRenderer<out ceui.pixiv.feeds.FeedItem, out androidx.viewbinding.ViewBinding>> = listOf(feedRenderer<ArtlistCardItem, ArtlistViewBinding>(
        inflate = { inflater, _, _ -> ArtlistViewBinding(ArtlistCardView(inflater.context)) },
        recycle = { it.binding.root.recycle() },
    ) { cell ->
        cell.binding.root.bind(cell.item.list, model.picking) {
            if (model.busy.value) return@bind
            val item = cell.item.list
            if (!model.picking) ArtlistNavigation.open(requireContext(), item.id)
            else if (!item.containsWork) {
                if (model.batchPicking) confirmBatchAdd(item) else model.addTo(item)
            }
        }
    })

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): ceui.pixiv.feeds.FeedSkeletonView = ArtlistSkeleton(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val picking = model.picking
        val bottom = view.findViewById<FrameLayout>(R.id.artlist_actions)
        setupV3Toolbar(view, getString(if (picking) R.string.artlist_add else R.string.artlist_title), bottom)
        val filterHost = view.findViewById<FrameLayout>(R.id.artlist_filters)
        val pickerHint = view.findViewById<TextView>(R.id.artlist_picker_hint).apply {
            isVisible = picking
            typeface = context.v3Font(400)
            lineHeightRatio(1.5f)
            movementMethod = ScrollingMovementMethod.getInstance()
            isVerticalScrollBarEnabled = true
        }
        // Long names, errors and enlarged fonts must leave room to reach the target list.
        view.addOnLayoutChangeListener { _, _, top, _, bottomEdge, _, _, _, _ ->
            val limit = (bottomEdge - top) / 3
            if (pickerHint.maxHeight != limit) pickerHint.maxHeight = limit
        }
        filterHost.isVisible = !picking
        if (!picking) {
            filters = LibraryFilterViews(requireContext()).choiceChips(
                listOf(R.string.artlist_explore, R.string.artlist_created, R.string.artlist_saved).map(::getString),
                scroll = true,
            ) { selectScope(scopes[it]) }.also { filterHost.addView(it.view); it.select(scopes.indexOf(model.scope)) }
        }
        val create = requireContext().artlistButton(getString(R.string.artlist_create), true) { create() }
        bottom.addView(create, FrameLayout.LayoutParams(-1, -2))
        feedBinding.feedListView.itemAnimator = null
        IllustMuteStore.revisionLive.observe(viewLifecycleOwner) {
            feedAdapter?.let { adapter ->
                adapter.currentList.forEachIndexed { position, item ->
                    if (item is ArtlistCardItem) adapter.notifyItemChanged(position)
                }
            }
        }
        SessionManager.loggedInAccount.observe(viewLifecycleOwner) {
            if (model.accountChanged()) {
                if (picking) requireActivity().finish()
                else { feedViewModel.mutateItems { emptyList() }; feedViewModel.refresh() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(model.busy, model.batchProgress) { busy, progress -> busy to progress }.collect { (busy, progress) ->
                    create.isEnabled = !busy
                    feedBinding.feedListView.isEnabled = !busy
                    pickerHint.text = buildString {
                        if (progress != null) {
                            append(getString(R.string.artlist_bulk_progress, progress.completed, progress.total, progress.name))
                            progress.error?.let { append("\n").append(it) }
                            if (busy) append("\n").append(getString(R.string.artlist_bulk_running_hint))
                            if (!busy && progress.completed < progress.total) append("\n").append(getString(R.string.artlist_bulk_retry))
                            append("\n\n")
                        }
                        append(getString(R.string.artlist_public_hint))
                    }
                    pickerHint.setTextColor(requireContext().color(if (progress?.error != null) R.color.v3_danger else R.color.v3_text_2))
                } }
                launch { model.notice.collect { notice ->
                    notice ?: return@collect
                    model.consumeNotice()
                    notice.message?.let(Common::showToast)
                    if (notice.finish) requireActivity().finish()
                } }
                launch { combine(ArtlistRepository.revision, model.busy) { revision, busy -> revision to busy }.collect { (revision, busy) ->
                    // Each successful batch item invalidates the repository. Refresh once after
                    // the batch stops, rather than reloading the picker for every added work.
                    if (!busy && model.consumeRevisionChange(revision)) feedViewModel.refresh()
                } }
            }
        }
    }

    private fun selectScope(scope: String) {
        if (scope == model.scope) return
        model.scope = scope
        filters?.select(scopes.indexOf(scope))
        feedViewModel.mutateItems { emptyList() }
        feedViewModel.refresh()
    }
    private fun confirmBatchAdd(list: Artlist) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.artlist_add)
            .setMessage(getString(R.string.artlist_bulk_confirm, list.name) + "\n\n" + getString(R.string.artlist_public_hint))
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.artlist_add, WitDialogAction.ACTION_PROP_POSITIVE) { dialog, _ ->
                dialog.dismiss()
                if (isAdded) model.addTo(list)
            }.show()
    }
    private fun create() {
        if (!model.busy.value) ArtlistNavigation.edit(requireContext(), work = model.work, returnToPicker = model.batchPicking)
    }
    override fun onDestroyView() { filters = null; super.onDestroyView() }
}
