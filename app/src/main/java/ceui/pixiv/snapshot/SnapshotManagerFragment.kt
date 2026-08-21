package ceui.pixiv.snapshot

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.lisa.utils.Common
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 离线快照管理页：仿照 FragmentHistoryTabs，使用 viewpager_with_tablayout。
 * Three tabs: 全部 | 插画 | 漫画，每个 tab 是双列瀑布流卡片。
 * 支持多选批量导出到 SAF 文件夹。
 */
class SnapshotManagerFragment : Fragment() {

    private var _binding: ViewpagerWithTablayoutBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSnapshot(uri)
    }

    private val exportFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) exportSelectedToFolder(uri)
    }

    private var inSelectionMode = false
    private var activeSelectionTab: SnapshotListFragment? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ViewpagerWithTablayoutBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.snapshot_manager_title)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        val tabs = listOf(
            getString(R.string.string_390) to null,   // 全部
            getString(R.string.type_illust) to "illust",
            getString(R.string.type_manga) to "manga",
        )
        val fragments = tabs.map { (_, filter) -> SnapshotListFragment.newInstance(filter) }

        binding.viewPager.adapter = object : FragmentPagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment = fragments[position]
            override fun getCount(): Int = tabs.size
            override fun getPageTitle(position: Int): CharSequence = tabs[position].first
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)

        binding.toolbar.inflateMenu(R.menu.menu_snapshot_manager)
        tintMenuIconsWhite()
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_snapshot -> {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    true
                }
                R.id.action_batch_export -> {
                    enterSelectionMode()
                    true
                }
                R.id.action_cancel_selection -> {
                    exitSelectionMode()
                    true
                }
                R.id.action_select_all_toggle -> {
                    activeSelectionTab?.toggleSelectAll()
                    refreshSelectionToolbar()
                    true
                }
                R.id.action_export_selected -> {
                    exportSelected()
                    true
                }
                else -> false
            }
        }

        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (inSelectionMode) exitSelectionMode()
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun currentSnapshotTab(): SnapshotListFragment? {
        val pos = binding.viewPager.currentItem
        val tag = "android:switcher:${binding.viewPager.id}:$pos"
        return childFragmentManager.findFragmentByTag(tag) as? SnapshotListFragment
    }

    private fun enterSelectionMode() {
        if (inSelectionMode) return
        val tab = currentSnapshotTab() ?: return
        if (!tab.hasItems()) {
            Common.showToast(getString(R.string.snapshot_empty))
            return
        }
        inSelectionMode = true
        activeSelectionTab = tab
        tab.enterSelectionMode()
        tab.onSelectionCountChanged = { refreshSelectionToolbar() }
        applySelectionToolbar()
    }

    private fun exitSelectionMode() {
        if (!inSelectionMode) return
        inSelectionMode = false
        activeSelectionTab?.onSelectionCountChanged = null
        activeSelectionTab?.exitSelectionMode()
        activeSelectionTab = null
        restoreNormalToolbar()
    }

    private fun applySelectionToolbar() {
        val menu = binding.toolbar.menu
        menu.setGroupVisible(R.id.group_normal, false)
        menu.setGroupVisible(R.id.group_selection, true)
        tintMenuIconsWhite()
        binding.toolbar.navigationIcon =
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_close_black_24dp)
                ?.mutate()?.apply { setTint(Color.WHITE) }
        binding.toolbar.setNavigationOnClickListener { exitSelectionMode() }
        refreshSelectionToolbar()
    }

    private fun restoreNormalToolbar() {
        val menu = binding.toolbar.menu
        menu.setGroupVisible(R.id.group_selection, false)
        menu.setGroupVisible(R.id.group_normal, true)
        tintMenuIconsWhite()
        binding.toolbarTitle.text = getString(R.string.snapshot_manager_title)
        binding.toolbar.navigationIcon =
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_back_white_shadow)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
    }

    private fun refreshSelectionToolbar() {
        val tab = activeSelectionTab ?: return
        val count = tab.selectedCount()
        binding.toolbarTitle.text = getString(R.string.snapshot_selected_count, count)
        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_export_selected)?.isEnabled = count > 0
        val allSelected = tab.isAllSelected()
        menu.findItem(R.id.action_select_all_toggle)?.apply {
            setIcon(if (allSelected) R.drawable.ic_deselect_24 else R.drawable.ic_select_all_24)
            setTitle(if (allSelected) R.string.bulk_select_clear_all else R.string.bulk_select_select_all)
        }
    }

    private fun tintMenuIconsWhite() {
        val menu = binding.toolbar.menu
        for (i in 0 until menu.size()) {
            menu.getItem(i)?.icon?.mutate()?.setTint(Color.WHITE)
        }
    }

    private fun exportSelected() {
        val tab = activeSelectionTab ?: return
        if (tab.selectedCount() == 0) return
        exportFolderLauncher.launch(null)
    }

    private fun exportSelectedToFolder(uri: android.net.Uri) {
        val tab = activeSelectionTab ?: return
        val items = tab.selectedSnapshots()
        if (items.isEmpty()) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    items.forEach { SnapshotRepository.exportToDirectory(requireContext(), it.manifest.snapshotId, uri) }
                }
                Common.showToast(getString(R.string.snapshot_export_success))
                exitSelectionMode()
            } catch (e: Exception) {
                Common.showToast(getString(R.string.snapshot_export_failed, e.message ?: ""))
            }
        }
    }

    private fun importSnapshot(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { SnapshotRepository.import(requireContext(), uri) }
                Common.showToast(getString(R.string.snapshot_import_success, manifest.title ?: manifest.snapshotId))
                reloadAllTabs()
            } catch (e: Exception) {
                Common.showToast(getString(R.string.snapshot_import_failed, e.message ?: ""))
            }
        }
    }

    private fun reloadAllTabs() {
        childFragmentManager.fragments
            .filterIsInstance<SnapshotListFragment>()
            .forEach { it.reload() }
    }

    companion object {
        const val ARG_SNAPSHOT_ID = "snapshotId"
    }
}
