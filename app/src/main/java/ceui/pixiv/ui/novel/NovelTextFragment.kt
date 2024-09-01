package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHomeViewpagerBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.PixivHtmlObject
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.refactor.viewBinding
import com.google.gson.Gson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class NovelTextFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val args by navArgs<NovelTextFragmentArgs>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val novelViewModel by pixivListViewModel {
        NovelTextDataSource(args.novelId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, novelViewModel)
        ObjectPool.get<Novel>(args.novelId).observe(viewLifecycleOwner) {
            binding.toolbarLayout.naviTitle.text = it.title
        }
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
    }
}