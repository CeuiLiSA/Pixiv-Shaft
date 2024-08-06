package ceui.pixiv.ui.task

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.PixivFragment
import ceui.refactor.viewBinding

class TaskStatusFragment : PixivFragment(R.layout.fragment_pixiv_list) {
    private val binding by viewBinding(FragmentPixivListBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val task = DownloadAllTask(requireContext()) {
            val items = mutableListOf<NamedUrl>()
            loadIllustsFromCache()?.forEach { illust ->
                val displayName = "pixiv_works_${illust.id}_p0.png"
                if (illust.page_count == 1) {
                    illust.meta_single_page?.original_image_url?.let {
                        items.add(NamedUrl(displayName, it))
                    }
                } else {
                    illust.meta_pages?.getOrNull(0)?.image_urls?.original?.let {
                        items.add(NamedUrl(displayName, it))
                    }
                }
            }
            items
        }

        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        binding.listView.adapter = adapter
        adapter.submitList(task.pendingTasks.map { TaskStatusHolder(it) })
    }
}