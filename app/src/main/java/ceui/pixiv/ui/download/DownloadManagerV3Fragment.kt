package ceui.pixiv.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

/**
 * V3 设计哲学的下载管理页：单页面 3 tab 容器。
 *
 * Tab 0: 批量队列    — 持久化 download_queue (DownloadQueueEntity)
 * Tab 1: 正在下载    — Manager.content 内存队列 (page-level DownloadItem)
 * Tab 2: 已完成      — DownloadEntity (DB)
 *
 * 顶部 V3 风格 stats chips：实时显示 3 类计数。每 2s 刷一次，仅 STARTED 时刷新。
 */
class DownloadManagerV3Fragment : Fragment() {

    private val sharedVm: DownloadManagerSharedViewModel by activityViewModels()

    private lateinit var queueChip: TextView
    private lateinit var activeChip: TextView
    private lateinit var doneChip: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_manager_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        queueChip = view.findViewById(R.id.queueChip)
        activeChip = view.findViewById(R.id.activeChip)
        doneChip = view.findViewById(R.id.doneChip)

        val pager = view.findViewById<ViewPager2>(R.id.viewPager)
        val tabs = view.findViewById<TabLayout>(R.id.tabLayout)

        pager.adapter = TabsAdapter(this)
        pager.offscreenPageLimit = 2

        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = when (pos) {
                0 -> "批量队列"
                1 -> "正在下载"
                2 -> "已完成"
                else -> ""
            }
        }.attach()

        // V3 stats chips 实时刷新
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedVm.snapshots().collect { s ->
                    queueChip.text = "队列  ${s.queuePending + s.queueDownloading}"
                    activeChip.text = "下载中  ${s.activeCount}"
                    doneChip.text = "已完成  ${s.queueSuccess}"
                }
            }
        }
    }

    private class TabsAdapter(host: Fragment) : FragmentStateAdapter(host) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> QueueListV3Fragment()
            1 -> ActiveListV3Fragment()
            2 -> DoneListV3Fragment()
            else -> error("unreachable: $position")
        }
    }
}
