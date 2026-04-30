package ceui.pixiv.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
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
 * Tab 0: 批量队列  · count → 持久化 download_queue 中 PENDING+DOWNLOADING
 * Tab 1: 正在下载  · count → Manager.content 内存队列大小
 * Tab 2: 已完成    · count → download_queue 里 SUCCESS 的累积
 *
 * 数字直接追加到 tab 文字后面，避免单独占一行。
 */
class DownloadManagerV3Fragment : Fragment() {

    private val sharedVm: DownloadManagerSharedViewModel by activityViewModels()

    private var tabs: TabLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_manager_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val pager = view.findViewById<ViewPager2>(R.id.viewPager)
        tabs = view.findViewById<TabLayout>(R.id.tabLayout)

        pager.adapter = TabsAdapter(this)
        pager.offscreenPageLimit = 2

        TabLayoutMediator(tabs!!, pager) { tab, pos ->
            tab.text = baseLabel(pos)
        }.attach()

        // 实时刷新 tab 文案末尾的数字
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedVm.snapshots().collect { s ->
                    setTabCount(0, s.queuePending + s.queueDownloading)
                    setTabCount(1, s.activeCount)
                    setTabCount(2, s.queueSuccess)
                }
            }
        }
    }

    override fun onDestroyView() {
        tabs = null
        super.onDestroyView()
    }

    private fun baseLabel(pos: Int): String = when (pos) {
        0 -> "批量队列"
        1 -> "正在下载"
        2 -> "已完成"
        else -> ""
    }

    private fun setTabCount(pos: Int, count: Int) {
        val t = tabs?.getTabAt(pos) ?: return
        val base = baseLabel(pos)
        t.text = if (count > 0) "$base  $count" else base
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
