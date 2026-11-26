package ceui.pixiv.ui.pinned

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.inSpans
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellItemPinnedTagBinding
import ceui.lisa.databinding.FragmentPinnedTabListBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.user.binding_loadSquareMedia
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.cardSurface
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 「我置顶的内容」的「标签」Tab（[PinnedTabsFragment] 第 1 页）。
 *
 * 与宿主共享 [PinnedContentViewModel]，自动承接多语言关键词过滤、Tab 实时计数与跨 Tab 同步。
 */
class PinnedTagsFragment : Fragment(R.layout.fragment_pinned_tab_list) {

    private val binding by viewBinding(FragmentPinnedTabListBinding::bind)
    private val viewModel: PinnedContentViewModel by viewModels({ requireParentFragment() })

    private var adapter: PinnedTagsAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val listAdapter = PinnedTagsAdapter(
            onClick = { item -> openSearchEntity(requireContext(), item.entity, lifecycleScope) },
            onLongClick = ::showItemMenu,
            onUnpin = ::onClickDeletePinnedTag,
        ).also { adapter = it }

        PinnedTabListHelper.setup(
            binding = binding,
            adapter = listAdapter,
            onRefresh = { viewModel.refreshAll(showSpinner = true) },
            onClearSearch = { viewModel.clearSearchQuery() },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.refreshing.collect { binding.refreshLayout.isRefreshing = it }
                }
                launch {
                    combine(
                        viewModel.filteredTags,
                        viewModel.searchQuery,
                        viewModel.hasLoaded,
                    ) { items, query, loaded -> Triple(items, query, loaded) }
                        .collect { (items, query, loaded) ->
                            listAdapter.submitList(items)
                            PinnedTabListHelper.renderEmptyState(
                                binding = binding,
                                hasLoaded = loaded,
                                isEmpty = items.isEmpty(),
                                query = query,
                                defaultEmptyRes = R.string.pinned_empty,
                            )
                        }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        adapter = null
        super.onDestroyView()
    }

    private fun showItemMenu(item: PinnedTagItemHolder) {
        val ctx = context ?: return
        val keyword = item.entity.keyword.orEmpty()
        val translated = item.tag.translated_name?.takeIf { it.isNotBlank() && it != keyword }
        val labels = mutableListOf(ctx.getString(R.string.v3_tag_menu_copy_original))
        val actions = mutableListOf<() -> Unit>({ Common.copy(ctx, keyword) })
        if (!item.isCombo && translated != null) {
            labels += ctx.getString(R.string.v3_tag_menu_copy_translation)
            actions += { Common.copy(ctx, translated) }
        }
        labels += ctx.getString(R.string.string_443)
        actions += { onClickDeletePinnedTag(item) }

        WitDialog.MenuDialogBuilder(ctx)
            .setTitle(item.displayName)
            .addItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions[which].invoke()
            }
            .show()
    }

    private fun onClickDeletePinnedTag(item: PinnedTagItemHolder) {
        val ctx = context ?: return
        val entity = item.entity
        val displayName = item.displayName
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(getString(R.string.unpin_tag_confirm_message, displayName))
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_443, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                viewModel.deletePinnedTag(entity)
                Common.showToast(R.string.unpin_tag_success)
                dialog.dismiss()
            }
            .show()
    }

    fun showClearAllDialog() {
        val ctx = context ?: return
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(R.string.clear_pinned_tags_msg)
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                viewModel.clearPinnedTags()
                Common.showToast(R.string.pinned_tags_cleared)
                dialog.dismiss()
            }
            .show()
    }

    private class PinnedTagsAdapter(
        private val onClick: (PinnedTagItemHolder) -> Unit,
        private val onLongClick: (PinnedTagItemHolder) -> Unit,
        private val onUnpin: (PinnedTagItemHolder) -> Unit,
    ) : ListAdapter<PinnedTagItemHolder, PinnedTagsAdapter.Holder>(DIFF) {

        class Holder(val binding: CellItemPinnedTagBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = CellItemPinnedTagBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            val ctx = binding.root.context
            binding.root.background = ctx.cardSurface(22)
            binding.pinnedTagTitle.typeface = ctx.v3Font(600)
            binding.pinnedTagSubtitle.typeface = ctx.v3Font(400)
            binding.deletePin.background = ctx.ripple(null, shape(999f, Color.WHITE))
            val holder = Holder(binding)
            binding.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
            binding.root.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick(getItem(pos))
                true
            }
            binding.deletePin.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onUnpin(getItem(pos))
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val b = holder.binding
            val item = getItem(position)
            b.illustsPreview.isVisible = item.hasPreview
            b.illust1.binding_loadSquareMedia(item.illust0)
            b.illust2.binding_loadSquareMedia(item.illust1)
            b.illust3.binding_loadSquareMedia(item.illust2)

            val res = b.root.resources
            if (item.isCombo) {
                val accent = V3Palette.from(b.root.context).textAccent
                b.pinnedTagTitle.maxLines = 2
                b.pinnedTagTitle.text = SpannableStringBuilder().apply {
                    item.terms.forEachIndexed { index, term ->
                        if (index > 0) {
                            append(" ")
                            inSpans(ForegroundColorSpan(accent)) { append("+") }
                            append(" ")
                        }
                        append(term)
                    }
                }
                b.pinnedTagSubtitle.text =
                    res.getQuantityString(R.plurals.pinned_tag_combo_count, item.terms.size, item.terms.size)
            } else {
                b.pinnedTagTitle.maxLines = 1
                b.pinnedTagTitle.text = item.tag.translated_name ?: item.tag.name
                b.pinnedTagSubtitle.text = item.tag.name
            }
            b.pinnedTagSubtitle.isVisible = item.showSubtitle
        }

        override fun onViewRecycled(holder: Holder) {
            super.onViewRecycled(holder)
            holder.binding.illust1.clearGlideOnRecycle()
            holder.binding.illust2.clearGlideOnRecycle()
            holder.binding.illust3.clearGlideOnRecycle()
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<PinnedTagItemHolder>() {
                override fun areItemsTheSame(
                    oldItem: PinnedTagItemHolder,
                    newItem: PinnedTagItemHolder,
                ): Boolean = oldItem.entity.id == newItem.entity.id

                override fun areContentsTheSame(
                    oldItem: PinnedTagItemHolder,
                    newItem: PinnedTagItemHolder,
                ): Boolean = oldItem.entity.keyword == newItem.entity.keyword &&
                    oldItem.entity.previewIllustsJson == newItem.entity.previewIllustsJson &&
                    oldItem.entity.searchType == newItem.entity.searchType
            }
        }
    }
}
