package ceui.pixiv.ui.download

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.core.ManagerReactive
import ceui.pixiv.download.ImagePageSaver
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Click-only inner tabs leave horizontal swipes to the outer download manager. */
@AndroidEntryPoint
class ActiveTasksV3Fragment : Fragment(R.layout.fragment_active_tasks_v3) {
    @Inject lateinit var saver: ImagePageSaver
    @Inject lateinit var queue: QueueDownloadManager
    private val sharedVm: DownloadManagerSharedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabs = view.findViewById<TabLayout>(R.id.activeSectionTabs)
        val initial = (savedInstanceState?.getInt(SECTION)
            ?: sharedVm.activeSection.value).coerceIn(0, 1)
        tabs.addTab(tabs.newTab().setText(R.string.dlmgr_section_downloads), initial == 0)
        tabs.addTab(tabs.newTab().setText(R.string.dlmgr_section_saves), initial == 1)
        fun showSection(position: Int) {
            sharedVm.setActiveSection(position)
            val tag = "active-section-$position"
            if (childFragmentManager.findFragmentById(R.id.activeSectionContent)?.tag == tag) return
            childFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.activeSectionContent,
                    if (position == 0) ActiveListV3Fragment() else PageSavesV3Fragment(), tag)
                .commit()
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showSection(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        showSection(initial)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(ManagerReactive.contentFlow, queue.ugoiraInFlightFlow, saver.states) {
                        downloads, ugoiras, saves ->
                    downloads.size + ugoiras.size to saves.values.count {
                        it.target is ImagePageSaver.Target.Original
                    }
                }.distinctUntilChanged().collect { (downloads, saves) ->
                    tabs.getTabAt(0)?.text = getString(R.string.dlmgr_section_count,
                        getString(R.string.dlmgr_section_downloads), downloads)
                    tabs.getTabAt(1)?.text = getString(R.string.dlmgr_section_count,
                        getString(R.string.dlmgr_section_saves), saves)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SECTION, sharedVm.activeSection.value)
        super.onSaveInstanceState(outState)
    }

    private companion object { const val SECTION = "active-section" }
}
