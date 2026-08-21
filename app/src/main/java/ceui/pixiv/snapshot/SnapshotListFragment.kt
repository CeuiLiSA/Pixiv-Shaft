package ceui.pixiv.snapshot

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { SnapshotRepository.export(requireContext(), id, uri) }
                    Common.showToast(getString(R.string.snapshot_export_success))
                } catch (e: Exception) {
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
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun reload() {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { SnapshotRepository.list(requireContext()) }
            val list = if (filter == null) all else all.filter { it.manifest.type == filter }
            if (_binding == null) return@launch
            adapter.submitList(list)
            binding.emptyHint.isVisible = list.isEmpty()
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
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        if (Shaft.sSettings.isUseArtworkV3()) {
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "快照查看")
        } else {
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "快照经典查看")
        }
        intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_ID, summary.manifest.snapshotId)
        startActivity(intent)
    }

    private fun exportSnapshot(summary: SnapshotSummary) {
        pendingExportId = summary.manifest.snapshotId
        val safeTitle = summary.manifest.title
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "snapshot"
        exportLauncher.launch("${safeTitle}_${summary.manifest.illustId}$SNAPSHOT_EXTENSION")
    }

    private fun confirmDelete(summary: SnapshotSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.snapshot_delete)
            .setMessage(getString(R.string.snapshot_delete_confirm, summary.manifest.title ?: summary.manifest.snapshotId))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.snapshot_delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { SnapshotRepository.delete(requireContext(), summary.manifest.snapshotId) }
                    if (ok) Common.showToast(getString(R.string.snapshot_delete_success)) else Common.showToast(getString(R.string.snapshot_delete_failed))
                    reload()
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
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(summary.manifest.createdAt))
        binding.time.text = context.getString(
            R.string.snapshot_meta_format,
            time,
            Formatter.formatShortFileSize(context, summary.totalSize),
        )
        binding.commentTag.isVisible = summary.manifest.includeComments
        binding.originalTag.isVisible = summary.manifest.includeOriginal

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