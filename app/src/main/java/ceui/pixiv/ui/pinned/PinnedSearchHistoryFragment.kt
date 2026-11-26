package ceui.pixiv.ui.pinned

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import ceui.lisa.databinding.CellItemSearchHistoryBinding
import ceui.lisa.databinding.FragmentPinnedTabListBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.cardSurface
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 「我置顶的内容」的「搜索历史」Tab（[PinnedTabsFragment] 第 3 页，issue #1399）。
 *
 * 展示 `search_table` 中 `pinned = 0` 的**全量**本地历史搜索记录（打破搜索首页 50 条截断），
 * 支持中 / 日 / 英关键词过滤、一键置顶到「标签」、复制、单条确认删除及整页清空（仅清未置顶）。
 */
class PinnedSearchHistoryFragment : Fragment(R.layout.fragment_pinned_tab_list) {

    private val binding by viewBinding(FragmentPinnedTabListBinding::bind)
    private val viewModel: PinnedContentViewModel by viewModels({ requireParentFragment() })

    private var recyclerView: RecyclerView? = null
    private var adapter: SearchHistoryAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = binding.recyclerView

        val listAdapter = SearchHistoryAdapter(
            onClick = { item -> openSearchEntity(requireContext(), item.entity, lifecycleScope) },
            onLongClick = ::showItemMenu,
            onPin = ::onClickPin,
            onDelete = ::onClickDelete,
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
                        viewModel.filteredHistory,
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
                                defaultEmptyRes = R.string.pinned_history_empty,
                            )
                        }
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerView?.adapter = null
        recyclerView = null
        adapter = null
        super.onDestroyView()
    }

    private fun onClickPin(item: SearchHistoryItemHolder) {
        val ctx = context ?: return
        viewModel.pinHistoryItem(item.entity)
        Common.showToast(ctx.getString(R.string.search_pinned_snack, item.keyword))
    }

    private fun showItemMenu(item: SearchHistoryItemHolder) {
        val ctx = context ?: return
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels += ctx.getString(R.string.string_120)
        actions += { Common.copy(ctx, item.keyword) }
        if (item.canPinAsTag) {
            labels += ctx.getString(R.string.string_442)
            actions += { onClickPin(item) }
        }
        labels += ctx.getString(R.string.action_delete)
        actions += { onClickDelete(item) }

        WitDialog.MenuDialogBuilder(ctx)
            .setTitle(item.keyword)
            .addItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions[which].invoke()
            }
            .show()
    }

    private fun onClickDelete(item: SearchHistoryItemHolder) {
        val ctx = context ?: return
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.action_delete)
            .setMessage(ctx.getString(R.string.search_history_delete_confirm, item.keyword))
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.action_delete, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                viewModel.deleteHistoryItem(item.entity)
                Common.showToast(R.string.operate_success)
                dialog.dismiss()
            }
            .show()
    }

    fun showClearAllDialog() {
        val ctx = context ?: return
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_144)
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                viewModel.clearSearchHistory()
                Common.showToast(R.string.string_140)
                dialog.dismiss()
            }
            .show()
    }

    private class SearchHistoryAdapter(
        private val onClick: (SearchHistoryItemHolder) -> Unit,
        private val onLongClick: (SearchHistoryItemHolder) -> Unit,
        private val onPin: (SearchHistoryItemHolder) -> Unit,
        private val onDelete: (SearchHistoryItemHolder) -> Unit,
    ) : ListAdapter<SearchHistoryItemHolder, SearchHistoryAdapter.Holder>(DIFF) {

        private val timeFormatter = SearchHistoryTimeFormatter()

        class Holder(val binding: CellItemSearchHistoryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = CellItemSearchHistoryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            val ctx = binding.root.context
            val palette = V3Palette.from(ctx)
            binding.root.background = ctx.cardSurface(22)

            // V3 类别图标容器：17/17/17/7 异形圆角 + alpha15 主题浅底 + textAccent 图标
            val r = ctx.dpF(17f)
            val cut = ctx.dpF(7f)
            binding.typeIconTile.background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(r, r, r, r, r, r, cut, cut)
                setColor(palette.alpha15)
            }
            binding.typeIcon.imageTintList = ColorStateList.valueOf(palette.textAccent)

            binding.historyKeyword.typeface = ctx.v3Font(600)
            binding.historyMeta.typeface = ctx.v3Font(500)
            binding.historyMeta.fontFeatureSettings = "tnum"

            binding.pinAction.background = ctx.ripple(null, shape(999f, Color.WHITE))
            binding.deleteAction.background = ctx.ripple(null, shape(999f, Color.WHITE))

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
            binding.pinAction.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPin(getItem(pos))
            }
            binding.deleteAction.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onDelete(getItem(pos))
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val ctx = holder.binding.root.context
            holder.binding.typeIcon.setImageResource(searchTypeIconRes(item.searchType))
            holder.binding.historyKeyword.text = item.keyword
            val typeLabel = ctx.getString(searchTypeLabelRes(item.searchType))
            val timeLabel = timeFormatter.format(ctx, item.searchTime)
            holder.binding.historyMeta.text = if (timeLabel.isNotEmpty()) {
                "$typeLabel · $timeLabel"
            } else {
                typeLabel
            }
            holder.binding.pinAction.isVisible = item.canPinAsTag
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<SearchHistoryItemHolder>() {
                override fun areItemsTheSame(
                    oldItem: SearchHistoryItemHolder,
                    newItem: SearchHistoryItemHolder,
                ): Boolean = oldItem.entity.id == newItem.entity.id

                override fun areContentsTheSame(
                    oldItem: SearchHistoryItemHolder,
                    newItem: SearchHistoryItemHolder,
                ): Boolean = oldItem.keyword == newItem.keyword &&
                    oldItem.searchType == newItem.searchType &&
                    oldItem.searchTime == newItem.searchTime &&
                    oldItem.entity.isPinned == newItem.entity.isPinned
            }
        }
    }
}
