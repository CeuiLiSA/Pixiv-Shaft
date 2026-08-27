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

/**
 * 离线快照管理页的单个 Tab：双列瀑布流卡片，按 manifest.type 过滤。
 * filter == null 表示“全部”。
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
                withContext(Dispatchers.IO) { SnapshotRepository.list(appContext) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.w(e, "[Snapshot] list failed")
                return@launch
            }
            val list = if (filter == null) all else all.filter { it.manifest.type == filter }
            if (_binding == null) return@launch
            if (resetScroll) adapter.submitList(null)
            adapter.submitList(list)
            binding.emptyHint.isVisible = list.isEmpty()
            if (resetScroll) binding.snapshotList.scrollToPosition(0)
        }
    }

    fun enterSelectionMode() {
        if (adapter.currentList.isEmpty()) return
        adapter.setSelectionMode(true)
        onSelectionCountChanged?.invoke(0)
    }

    fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        onSelectionCountChanged?.invoke(0)
    }

    fun selectedSnapshots(): List<SnapshotSummary> =
        adapter.currentList.filter { it.manifest.snapshotId in adapter.selectedIds }

    fun selectedCount(): Int = adapter.selectedIds.size

    fun hasItems(): Boolean = adapter.currentList.isNotEmpty()

    fun isAllSelected(): Boolean = adapter.isAllSelected()

    fun toggleSelectAll() {
        if (adapter.isAllSelected()) {
            adapter.clearSelection()
        } else {
            adapter.selectAll()
        }
        onSelectionCountChanged?.invoke(adapter.selectedIds.size)
    }

    private fun openSnapshot(summary: SnapshotSummary) {
        val snapshotId = summary.manifest.snapshotId
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            // 先确保内存缓存有完整快照数据，详情页/大图页直接同步消费，避免异步加载竞态。
            // loadViewerData 对「目录已被删 / illust.json 损坏」是会抛的(卡片可能是上一次
            // 列表快照,点下去时那份快照已经不在了)——裸 launch 里逃逸出去就是崩进程,
            // 和 FragmentIllust / ImageDetailActivity 那两个入口一样就地兜住:提示 + 刷新列表。
            try {
                withContext(Dispatchers.IO) {
                    if (SnapshotRuntimeCache.get(snapshotId) == null) {
                        val data = SnapshotRepository.loadViewerData(appContext, snapshotId)
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
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "快照查看")
            } else {
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "快照经典查看")
            }
            intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_ID, snapshotId)
            startActivity(intent)
        }
    }

    private fun exportSnapshot(summary: SnapshotSummary) {
        pendingExportId = summary.manifest.snapshotId
        exportLauncher.launch(summary.manifest.safeExportFileName())
    }

    private fun confirmDelete(summary: SnapshotSummary) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.snapshot_delete)
            .setMessage(getString(R.string.snapshot_delete_confirm, summary.manifest.title ?: summary.manifest.snapshotId))
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.snapshot_delete, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                val appContext = requireContext().applicationContext
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { SnapshotRepository.delete(appContext, summary.manifest.snapshotId) }
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
    private val onOpen: (SnapshotSummary) -> Unit,
    private val onExport: (SnapshotSummary) -> Unit,
    private val onDelete: (SnapshotSummary) -> Unit,
) : ListAdapter<SnapshotSummary, SnapshotViewHolder>(DIFF) {

    var selectionMode: Boolean = false
        private set

    val selectedIds = linkedSetOf<String>()
    var onSelectionToggle: ((SnapshotSummary) -> Unit)? = null

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(summary: SnapshotSummary) {
        if (!selectionMode) return
        if (!selectedIds.add(summary.manifest.snapshotId)) {
            selectedIds.remove(summary.manifest.snapshotId)
        }
        onSelectionToggle?.invoke(summary)
        notifyDataSetChanged()
    }

    fun selectAll() {
        if (!selectionMode) return
        selectedIds.clear()
        selectedIds.addAll(currentList.map { it.manifest.snapshotId })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun isAllSelected(): Boolean =
        currentList.isNotEmpty() && selectedIds.size == currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnapshotViewHolder {
        val binding = ItemSnapshotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SnapshotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SnapshotViewHolder, position: Int) {
        val summary = getItem(position)
        holder.bind(
            summary = summary,
            onOpen = onOpen,
            onExport = onExport,
            onDelete = onDelete,
            selectionMode = selectionMode,
            selected = summary.manifest.snapshotId in selectedIds,
            onToggleSelection = { toggleSelection(summary) },
        )
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SnapshotSummary>() {
            override fun areItemsTheSame(oldItem: SnapshotSummary, newItem: SnapshotSummary): Boolean =
                oldItem.manifest.snapshotId == newItem.manifest.snapshotId

            override fun areContentsTheSame(oldItem: SnapshotSummary, newItem: SnapshotSummary): Boolean =
                oldItem == newItem
        }
    }
}

private class SnapshotViewHolder(
    private val binding: ItemSnapshotBinding,
) : RecyclerView.ViewHolder(binding.root) {

    private companion object {
        // 只在主线程 bind 里用,一份即可:每格现构造一个 SimpleDateFormat 等于每次滑动
        // 都重新解析一遍 pattern + 查一遍 Locale/TimeZone,而格式是常量。
        val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    fun bind(
        summary: SnapshotSummary,
        onOpen: (SnapshotSummary) -> Unit,
        onExport: (SnapshotSummary) -> Unit,
        onDelete: (SnapshotSummary) -> Unit,
        selectionMode: Boolean,
        selected: Boolean,
        onToggleSelection: () -> Unit,
    ) {
        val context = binding.root.context
        if (summary.coverFile != null) {
            Glide.with(binding.cover).load(summary.coverFile).into(binding.cover)
        } else {
            Glide.with(binding.cover).clear(binding.cover)
        }
        binding.title.text = summary.manifest.title ?: context.getString(R.string.snapshot_untitled)
        binding.author.text = listOfNotNull(
            summary.manifest.authorName,
            "ID ${summary.manifest.authorId ?: summary.manifest.illustId}",
        ).joinToString(" · ")
        val time = TIME_FORMAT.format(Date(summary.manifest.createdAt))
        binding.time.text = context.getString(
            R.string.snapshot_meta_format,
            time,
            Formatter.formatShortFileSize(context, summary.totalSize),
        )
        binding.commentTag.isVisible = summary.manifest.includeComments
        binding.originalTag.isVisible = summary.manifest.includeOriginal

        val isR18 = (summary.manifest.xRestrict ?: 0) > 0
        val pageCount = summary.manifest.pageCount ?: 1
        binding.r18Badge.isVisible = isR18
        binding.pSize.isVisible = pageCount > 1
        if (pageCount > 1) {
            binding.pSize.text = String.format(Locale.getDefault(), "%dP", pageCount)
        }
        // 多选勾标固定在左上角；进入多选态时隐藏角标行，避免和勾标叠在一起。
        binding.badgeRow.isVisible = !selectionMode

        HistorySelectBadge.bindSelection(binding.selectCheck, binding.deleteButton, selectionMode, selected)
        if (selectionMode) {
            binding.root.setOnClickListener { onToggleSelection() }
            binding.root.setOnLongClickListener(null)
            binding.deleteButton.setOnClickListener(null)
        } else {
            binding.root.setOnClickListener { onOpen(summary) }
            binding.root.setOnLongClickListener {
                onExport(summary)
                true
            }
            binding.deleteButton.setOnClickListener { onDelete(summary) }
        }
    }
}