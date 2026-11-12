package ceui.pixiv.ui.artlist

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.launch

class ArtlistDetailFragment : IllustFeedFragment(R.layout.fragment_artlist_detail) {
    private val model: ArtlistModel by viewModels()
    override val feedViewModel by feedViewModels<String>(autoLoad = false) { model.works }
    private var boundContentState: Pair<Boolean, Boolean>? = null
    private val currentList: Artlist? get() = feedViewModel.uiState.value.items.filterIsInstance<ArtlistHeaderItem>().firstOrNull()?.list
    // Public previews must not replace the current viewer's bookmark/follow state or full detail.
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    override fun onCreateRenderers() = listOf(feedRenderer<ArtlistHeaderItem, ArtlistViewBinding>(
        inflate = { inflater, _, _ -> ArtlistViewBinding(ArtlistCardView(inflater.context, header = true)) },
        fullSpan = true,
        recycle = { it.binding.root.recycle() },
    ) { cell ->
        val list = cell.item.list
        val card = cell.binding.root
        card.bind(list)
        val content = contentState(feedViewModel.uiState.value)
        boundContentState = content
        val (hasWorks, empty) = content
        if (hasWorks) card.action(getString(R.string.artlist_browse), true) {
            currentIllustItems().firstOrNull()?.let(::openDetail)
        }
        if (list.uid == SessionManager.loggedInUid) {
            card.action(getString(R.string.artlist_edit)) { ArtlistNavigation.edit(requireContext(), list.id) }
        } else {
            card.action(getString(if (list.saved) R.string.artlist_collected else R.string.artlist_collect), !hasWorks) {
                model.mutate { uid -> ArtlistRepository.save(uid, list.id, !list.saved); null }
            }
        }
        if (empty) card.empty(getString(if (list.workCount == 0) R.string.artlist_works_empty else R.string.artlist_filtered_empty))
    }) + super.onCreateRenderers()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = setupV3Toolbar(view, getString(R.string.artlist_title), feedBinding.feedListView)
        toolbar.menu.add(R.string.artlist_share).setOnMenuItemClickListener { share(); true }
        toolbar.menu.add(R.string.more_actions).setOnMenuItemClickListener { more(); true }
        feedBinding.feedListView.itemAnimator = null
        IllustMuteStore.revisionLive.observe(viewLifecycleOwner) { rebindHeader() }
        SessionManager.loggedInAccount.observe(viewLifecycleOwner) {
            if (model.accountChanged()) { feedViewModel.mutateItems { emptyList() }; feedViewModel.refresh() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.notice.collect { notice ->
                    notice ?: return@collect
                    model.consumeNotice(); notice.message?.let(Common::showToast)
                    if (notice.finish) requireActivity().finish()
                } }
                launch { ArtlistRepository.revision.collect { revision ->
                    if (model.consumeRevisionChange(revision)) feedViewModel.refresh()
                } }
            }
        }
    }

    private fun contentState(state: FeedUiState): Pair<Boolean, Boolean> {
        val hasWorks = state.visibleItems.any { it is IllustFeedItem }
        return hasWorks to (!hasWorks && state.reachedEnd)
    }

    override fun onListCommitted(state: FeedUiState) {
        super.onListCommitted(state)
        // Appended pages may all be filtered out. Derive the header from the whole feed,
        // including its terminal state, instead of freezing the first page's empty flag.
        if (boundContentState != contentState(state)) rebindHeader()
    }

    private fun rebindHeader() {
        val adapter = feedAdapter ?: return
        val position = adapter.currentList.indexOfFirst { it is ArtlistHeaderItem }
        if (position >= 0) adapter.notifyItemChanged(position)
    }

    override fun onDestroyView() { boundContentState = null; super.onDestroyView() }

    private fun share() {
        val list = currentList ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.artlist_share_text, list.name, "https://pixshaft.com/artlists/${list.id}"))
        }, getString(R.string.artlist_share)))
    }

    private fun more() {
        val list = currentList ?: return
        showV3Menu("ArtlistMenu") {
            item(getString(R.string.artlist_share), R.drawable.ic_share_black_24dp) { share() }
            item(getString(R.string.artlist_more_add), R.drawable.ic_add_black_24dp) {
                startActivity(Intent(requireContext(), TemplateActivity::class.java)
                    .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SEARCH.key))
            }
            if (list.uid == SessionManager.loggedInUid) {
                item(getString(R.string.artlist_edit), R.drawable.ic_collections_black_24dp) { ArtlistNavigation.edit(requireContext(), list.id) }
                item(getString(R.string.artlist_delete), R.drawable.ic_delete_black_24dp) {
                    confirm(getString(R.string.artlist_delete), getString(R.string.artlist_delete_confirm, list.name)) {
                        model.mutate(finish = true) { uid -> ArtlistRepository.delete(uid, list.id); null }
                    }
                }
            }
        }
    }

    internal fun canEdit(): Boolean = currentList?.uid == SessionManager.loggedInUid
    internal fun setCover(illust: Illust) { model.mutate { uid ->
        ArtlistRepository.edit(uid, model.listId, ArtlistEdit(coverId = illust.id)); null
    } }
    internal fun remove(illust: Illust) {
        confirm(getString(R.string.artlist_remove), getString(R.string.artlist_remove_confirm, illust.title.orEmpty())) {
            model.mutate { uid -> ArtlistRepository.remove(uid, model.listId, illust.id); null }
        }
    }
    private fun confirm(title: String, message: String, action: () -> Unit) {
        WitDialog.MessageDialogBuilder(requireContext()).setTitle(title).setMessage(message)
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, title, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ -> dialog.dismiss(); action() }.show()
    }
}
