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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentSnapshotManagerBinding
import ceui.lisa.databinding.ItemSnapshotBinding
import ceui.lisa.utils.Common
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnapshotManagerFragment : Fragment() {

    private var _binding: FragmentSnapshotManagerBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val adapter = SnapshotAdapter(
        onOpen = { openSnapshot(it) },
        onExport = { exportSnapshot(it) },
        onDelete = { confirmDelete(it) },
    )

    private var pendingExportId: String? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSnapshot(uri)
    }

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
    ): View = FragmentSnapshotManagerBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.snapshotList.layoutManager = LinearLayoutManager(requireContext())
        binding.snapshotList.adapter = adapter
        binding.importButton.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
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

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { SnapshotRepository.list(requireContext()) }
            if (_binding == null) return@launch
            adapter.submitList(list)
            binding.emptyHint.isVisible = list.isEmpty()
        }
    }

    private fun openSnapshot(summary: SnapshotSummary) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "快照查看")
        intent.putExtra(ARG_SNAPSHOT_ID, summary.manifest.snapshotId)
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

    private fun importSnapshot(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { SnapshotRepository.import(requireContext(), uri) }
                Common.showToast(getString(R.string.snapshot_import_success, manifest.title ?: manifest.snapshotId))
                reload()
            } catch (e: Exception) {
                Common.showToast(getString(R.string.snapshot_import_failed, e.message ?: ""))
            }
        }
    }

    companion object {
        const val ARG_SNAPSHOT_ID = "snapshotId"
    }
}

private class SnapshotAdapter(
    private val onOpen: (SnapshotSummary) -> Unit,
    private val onExport: (SnapshotSummary) -> Unit,
    private val onDelete: (SnapshotSummary) -> Unit,
) : ListAdapter<SnapshotSummary, SnapshotViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnapshotViewHolder {
        val binding = ItemSnapshotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SnapshotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SnapshotViewHolder, position: Int) {
        holder.bind(getItem(position), onOpen, onExport, onDelete)
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
    ) {
        val context = binding.root.context
        if (summary.coverFile != null) {
            Glide.with(binding.cover).load(summary.coverFile).into(binding.cover)
        } else {
            Glide.with(binding.cover).clear(binding.cover)
        }
        binding.title.text = summary.manifest.title ?: context.getString(R.string.snapshot_untitled)
        binding.subtitle.text = listOfNotNull(
            summary.manifest.authorName,
            "ID ${summary.manifest.authorId ?: summary.manifest.illustId}",
        ).joinToString(" · ")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(summary.manifest.createdAt))
        binding.meta.text = context.getString(
            R.string.snapshot_meta_format,
            time,
            Formatter.formatShortFileSize(context, summary.totalSize),
        )
        binding.commentTag.isVisible = summary.manifest.includeComments
        binding.originalTag.isVisible = summary.manifest.includeOriginal

        binding.root.setOnClickListener { onOpen(summary) }
        binding.exportButton.setOnClickListener { onExport(summary) }
        binding.deleteButton.setOnClickListener { onDelete(summary) }
    }
}