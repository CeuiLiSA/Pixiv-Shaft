package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.view.LinearItemDecoration
import ceui.lisa.view.LinearItemDecorationKt
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.home.RecmdNovelDataSource
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.refactor.ppppx
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import jp.wasabeef.glide.transformations.BlurTransformation
import kotlin.getValue

class ArtworkFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<ArtworkFragmentArgs>()
    private val viewModel by constructVM({ safeArgs.illustId }) { illustId ->
        ArtworkViewModel(illustId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val liveIllust = ObjectPool.get<Illust>(safeArgs.illustId)
        liveIllust.observe(viewLifecycleOwner) { illust ->
            Glide.with(this)
                .load(GlideUrlChild(illust.image_urls?.large))
                .apply(bitmapTransform(BlurTransformation(25, 3)))
                .into(binding.pageBackground)
        }
        val ctx = requireContext()
        binding.listView.layoutManager = LinearLayoutManager(ctx)
        liveIllust.value?.let { illust ->
            binding.listView.addItemDecoration(LinearItemDecorationKt(16.ppppx, illust.page_count))
        }
        setUpRefreshState(binding, viewModel, ListMode.CUSTOM)
    }
}