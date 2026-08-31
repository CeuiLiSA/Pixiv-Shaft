package ceui.pixiv.snapshot

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentSnapshotListBinding
import ceui.lisa.databinding.ItemSnapshotBinding
import ceui.lisa.fragments.HistorySelectBadge
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 离线快照管理页的单个 Tab：双列瀑布流卡片，按 manifest.type 过滤。
 * filter == null 表示“全部”。
 *
 * 列表由正式快照（SnapshotSummary）和自动快照（AutoSnapshotSummary）合并而成：
 * 自动快照带“自动”标记，长按弹窗可转正/删除，多选模式下不显示勾选框。
 */
class SnapshotListFragment : Fragment() {

    private var _binding: FragmentSnapshotListBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val filter: String? by lazy(LazyThreadSafetyMode.NONE) {
        arguments?.getString(ARG_FILTER)
    }

    private val adapter = SnapshotAdapter(
        onOpen = { openSnapshot(it) },
        onExport = { exportSnapshot(it) },
        onDelete = { confirmDelete(it) },
        onLongPress = { onLongPress(it) },
    ).also { adapter ->
        adapter.onSelectionToggle = { onSelectionCountChanged?.invoke(adapter.selectedIds.size) }
    }

    /** 选中数量变化回调，由宿主 Tabs 页驱动 selection toolbar。 */
    var onSelectionCountChanged: ((Int) -> Unit)? = null

    private var pendingExportId: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val id = pendingExportId ?: return@registerForActivityResult
        if (uri != null) {
            val appContext = requireContext().applicationContext
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { SnapshotRepository.export(appContext, id, uri) }
                    Common.showToast(getString(R.string.snapshot_export_success))
                } catch (e: Exception) {
                    Timber.w(e, "[Snapshot] export failed, id=%s", id)
                    Common.showToast(getString(R.string.snapshot_export_failed, e.message ?: ""))
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = FragmentSnapshotListBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.snapshotList.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.snapshotList.itemAnimator = null
        binding.snapshotList.adapter = adapter
        reload(resetScroll = true)
    }

    override fun onResume() {
        super.onResume()
        // 例行刷新:不清空、不回顶。看完一个快照返回时列表内容通常一模一样,
        // Diff 出来是零变更,滚动位置原样保留。
        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * [resetScroll] 只在导入/删除这类**结构性变更**后传 true。
     *
     * 「先清空再提交 + 回顶」是为了让双列瀑布流按新顺序重新布局(否则新卡会被 Diff 塞进
     * 右列/旧列错位)——那是新卡进来时才需要付的代价。onResume 的例行刷新也这么干的话,
     * 每次从详情页返回列表都要整个闪一下并跳回顶部,快照一多就再也找不回刚才看到哪了。
     */
    fun reload(resetScroll: Boolean = false) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val all = try {
                withContext(Dispatchers.IO) {
                    val formal = SnapshotRepository.list(appContext).map { FormalSnapshotCard(it) }
                    val auto = AutoSnapshotRepository.listAuto(appContext).map { AutoSnapshotCard(it) }
                    (formal + auto)
                        .filter { filter == null || it.type == filter }
                        .sortedByDescending { it.createdAt }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.w(e, "[Snapshot] list failed")
                return@launch
            }
            if (_binding == null) return@launch
            if (resetScroll) adapter.submitList(null)
            adapter.submitList(all)
            binding.emptyHint.isVisible = all.isEmpty()
            if (resetScroll) binding.snapshotList.scrollToPosition(0)
        }
    }

    fun enterSelectionMode() {
        if (!hasItems()) return
        adapter.setSelectionMode(true)
        onSelectionCountChanged?.invoke(0)
    }

    fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        onSelectionCountChanged?.invoke(0)
    }

    /** 批量操作只包含正式快照；自动快照不可多选、不可批量导出/删除。 */
    fun selectedSnapshots(): List<SnapshotSummary> =
        adapter.currentList
            .filterIsInstance<FormalSnapshotCard>()
            .filter { it.snapshotId in adapter.selectedIds }
            .map { it.summary }

    fun selectedCount(): Int = adapter.selectedIds.size

    /** 批量选择只对正式快照有效；自动快照隐匿勾选框，不参与批量操作。 */
    fun hasItems(): Boolean = adapter.currentList.any { !it.isAuto }

    fun isAllSelected(): Boolean = adapter.isAllSelected()

    fun toggleSelectAll() {
        if (adapter.isAllSelected()) {
            adapter.clearSelection()
        } else {
            adapter.selectAll()
        }
        onSelectionCountChanged?.invoke(adapter.selectedIds.size)
    }

    private fun openSnapshot(card: SnapshotCard) {
        val snapshotId = card.snapshotId
        val isAuto = card.isAuto
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (SnapshotRuntimeCache.get(snapshotId) == null) {
                        val data = if (isAuto) {
                            AutoSnapshotRepository.loadAutoViewerData(appContext, snapshotId)
                        } else {
                            SnapshotRepository.loadViewerData(appContext, snapshotId)
                        }
                        SnapshotRuntimeCache.put(snapshotId, data)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.w(e, "[Snapshot] open failed, id=%s", snapshotId)
                Common.showToast(getString(R.string.snapshot_open_failed, e.message ?: ""))
                reload(resetScroll = true)
                return@launch
            }
            if (_binding == null) return@launch
            val intent = Intent(requireContext(), TemplateActivity::class.java)
            if (Shaft.sSettings.isUseArtworkV3()) {
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SNAPSHOT_VIEW.key)
            } else {
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SNAPSHOT_VIEW_CLASSIC.key)
            }
            intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_ID, snapshotId)
            intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, isAuto)
            startActivity(intent)
        }
    }

    private fun onLongPress(card: SnapshotCard) {
        when (card) {
            is AutoSnapshotCard -> showAutoActions(card)
            is FormalSnapshotCard -> exportSnapshot(card)
        }
    }

    private fun showAutoActions(card: AutoSnapshotCard) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(card.title ?: getString(R.string.snapshot_untitled))
            .addAction(R.string.snapshot_promote) { dialog, _ ->
                dialog.dismiss()
                confirmPromote(card)
            }
            .addAction(0, R.string.snapshot_delete, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                confirmDelete(card)
            }
            .show()
    }

    private fun confirmPromote(card: AutoSnapshotCard) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.snapshot_promote_title)
            .setMessage(R.string.snapshot_promote_confirm)
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(R.string.snapshot_promote) { dialog, _ ->
                dialog.dismiss()
                val appContext = requireContext().applicationContext
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            SnapshotPromoter.promote(appContext, card.snapshotId)
                        }
                        Common.showToast(getString(R.string.snapshot_promote_success))
                        reload()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Timber.w(e, "[Snapshot] promote failed, id=%s", card.snapshotId)
                        Common.showToast(getString(R.string.snapshot_promote_failed, e.message ?: ""))
                    }
                }
            }
            .show()
    }

    private fun exportSnapshot(card: SnapshotCard) {
        // 自动快照不允许导出，只有转正为 SnapshotManifest 后才可导出。
        if (card is AutoSnapshotCard) return
        val formal = card as FormalSnapshotCard
        pendingExportId = formal.snapshotId
        exportLauncher.launch(formal.summary.manifest.safeExportFileName())
    }

    private fun confirmDelete(card: SnapshotCard) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.snapshot_delete)
            .setMessage(getString(R.string.snapshot_delete_confirm, card.title ?: card.snapshotId))
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.snapshot_delete, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                val appContext = requireContext().applicationContext
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        if (card.isAuto) {
                            AutoSnapshotRepository.deleteAuto(appContext, card.snapshotId)
                        } else {
                            SnapshotRepository.delete(appContext, card.snapshotId)
                        }
                    }
                    if (ok) Common.showToast(getString(R.string.snapshot_delete_success)) else Common.showToast(getString(R.string.snapshot_delete_failed))
                    reload(resetScroll = true)
                }
            }
            .show()
    }

    companion object {
        private const val ARG_FILTER = "filter"

        fun newInstance(filter: String?): SnapshotListFragment {
            return SnapshotListFragment().apply {
                arguments = Bundle().apply {
                    if (filter != null) putString(ARG_FILTER, filter)
                }
            }
        }
    }
}

private class SnapshotAdapter(
    private val onOpen: (SnapshotCard) -> Unit,
    private val onExport: (SnapshotCard) -> Unit,
    private val onDelete: (SnapshotCard) -> Unit,
    private val onLongPress: (SnapshotCard) -> Unit,
) : ListAdapter<SnapshotCard, SnapshotViewHolder>(DIFF) {

    var selectionMode: Boolean = false
        private set

    val selectedIds = linkedSetOf<String>()
    var onSelectionToggle: ((SnapshotCard) -> Unit)? = null

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(card: SnapshotCard) {
        if (!selectionMode || card.isAuto) return
        if (!selectedIds.add(card.snapshotId)) {
            selectedIds.remove(card.snapshotId)
        }
        onSelectionToggle?.invoke(card)
        notifyDataSetChanged()
    }

    fun selectAll() {
        if (!selectionMode) return
        selectedIds.clear()
        selectedIds.addAll(currentList.filter { !it.isAuto }.map { it.snapshotId })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun isAllSelected(): Boolean {
        val selectable = currentList.count { !it.isAuto }
        return selectable > 0 && selectedIds.size == selectable
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnapshotViewHolder {
        val binding = ItemSnapshotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SnapshotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SnapshotViewHolder, position: Int) {
        val card = getItem(position)
        holder.bind(
            card = card,
            onOpen = onOpen,
            onExport = onExport,
            onDelete = onDelete,
            onLongPress = onLongPress,
            selectionMode = selectionMode,
            selected = card.snapshotId in selectedIds,
            onToggleSelection = { toggleSelection(card) },
        )
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SnapshotCard>() {
            override fun areItemsTheSame(oldItem: SnapshotCard, newItem: SnapshotCard): Boolean =
                oldItem.snapshotId == newItem.snapshotId

            override fun areContentsTheSame(oldItem: SnapshotCard, newItem: SnapshotCard): Boolean =
                oldItem == newItem
        }
    }
}

private class SnapshotViewHolder(
    private val binding: ItemSnapshotBinding,
) : RecyclerView.ViewHolder(binding.root) {

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    fun bind(
        card: SnapshotCard,
        onOpen: (SnapshotCard) -> Unit,
        onExport: (SnapshotCard) -> Unit,
        onDelete: (SnapshotCard) -> Unit,
        onLongPress: (SnapshotCard) -> Unit,
        selectionMode: Boolean,
        selected: Boolean,
        onToggleSelection: () -> Unit,
    ) {
        val context = binding.root.context
        if (card.coverFile != null) {
            Glide.with(binding.cover).load(card.coverFile).into(binding.cover)
        } else {
            Glide.with(binding.cover).clear(binding.cover)
        }
        binding.title.text = card.title ?: context.getString(R.string.snapshot_untitled)
        binding.author.text = listOfNotNull(
            card.authorName,
            "ID ${card.authorId ?: card.illustId}",
        ).joinToString(" · ")
        val time = TIME_FORMAT.format(Date(card.createdAt))
        binding.time.text = context.getString(
            R.string.snapshot_meta_format,
            time,
            Formatter.formatShortFileSize(context, card.totalSize),
        )
        binding.autoTag.isVisible = card.isAuto
        binding.commentTag.isVisible = card.includeComments
        binding.originalTag.isVisible = card.includeOriginal

        val isR18 = (card.xRestrict ?: 0) > 0
        val pageCount = card.pageCount ?: 1
        binding.r18Badge.isVisible = isR18
        binding.pSize.isVisible = pageCount > 1
        if (pageCount > 1) {
            binding.pSize.text = String.format(Locale.getDefault(), "%dP", pageCount)
        }
        // 多选勾标固定在左上角；进入多选态时隐藏角标行，避免和勾标叠在一起。
        binding.badgeRow.isVisible = !selectionMode

        HistorySelectBadge.bindSelection(binding.selectCheck, binding.deleteButton, selectionMode, selected)
        // 自动快照在多选态隐匿勾选框，且不可被点选进入批量操作。
        binding.selectCheck.isVisible = selectionMode && !card.isAuto

        if (selectionMode) {
            if (card.isAuto) {
                binding.root.setOnClickListener(null)
                binding.root.setOnLongClickListener(null)
                binding.deleteButton.setOnClickListener(null)
            } else {
                binding.root.setOnClickListener { onToggleSelection() }
                binding.root.setOnLongClickListener(null)
                binding.deleteButton.setOnClickListener(null)
            }
        } else {
            binding.root.setOnClickListener { onOpen(card) }
            binding.root.setOnLongClickListener {
                if (card.isAuto) onLongPress(card) else onExport(card)
                true
            }
            binding.deleteButton.setOnClickListener { onDelete(card) }
        }
    }
}