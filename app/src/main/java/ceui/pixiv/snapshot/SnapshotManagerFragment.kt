package ceui.pixiv.snapshot

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.lisa.utils.Common
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 离线快照管理页：仿照 FragmentHistoryTabs，使用 viewpager_with_tablayout。
 * Three tabs: 全部 | 插画 | 漫画，每个 tab 是双列瀑布流卡片。
 */
class SnapshotManagerFragment : Fragment() {

    private var _binding: ViewpagerWithTablayoutBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSnapshot(uri)
    }

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
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_snapshot -> {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
