package ceui.pixiv.ui.artlist

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
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
        cell.binding.root.bind(cell.item.list, model.work != null) {
            val item = cell.item.list
            val work = model.work
            if (work == null) ArtlistNavigation.open(requireContext(), item.id)
            else if (!item.containsWork) model.mutate(finish = true) { uid ->
                val updated = ArtlistRepository.add(uid, item.id, work)
                ceui.lisa.activities.Shaft.getContext().getString(R.string.artlist_add_done, updated.name)
            }
        }
    })

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): ceui.pixiv.feeds.FeedSkeletonView = ArtlistSkeleton(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val picking = model.work != null
        val bottom = view.findViewById<FrameLayout>(R.id.artlist_actions)
        setupV3Toolbar(view, getString(if (picking) R.string.artlist_add else R.string.artlist_title), bottom)
        val filterHost = view.findViewById<FrameLayout>(R.id.artlist_filters)
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
            if (model.accountChanged()) { feedViewModel.mutateItems { emptyList() }; feedViewModel.refresh() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.busy.collect { create.isEnabled = !it; feedBinding.feedListView.isEnabled = !it } }
                launch { model.notice.collect { notice ->
                    notice ?: return@collect
                    model.consumeNotice()
                    notice.message?.let(Common::showToast)
                    if (notice.finish) requireActivity().finish()
                } }
                launch { ArtlistRepository.revision.collect { revision ->
                    if (model.consumeRevisionChange(revision)) feedViewModel.refresh()
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
    private fun create() = ArtlistNavigation.edit(requireContext(), work = model.work)
    override fun onDestroyView() { filters = null; super.onDestroyView() }
}
