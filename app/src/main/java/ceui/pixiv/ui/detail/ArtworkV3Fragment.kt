package ceui.pixiv.ui.detail

import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.adapters.IAdapter
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.fragments.BaseFragment
import ceui.lisa.fragments.FragmentIllustArgs
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.ShareIllust
import ceui.loxia.ObjectPool
import ceui.loxia.threadSafeArgs
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick

class ArtworkV3Fragment : BaseFragment<FragmentArtworkV3Binding>() {

    private val safeArgs by threadSafeArgs<FragmentIllustArgs>()

    private val viewModel by viewModels<ArtworkV3ViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ArtworkV3ViewModel(safeArgs.illustId.toLong()) as T
            }
        }
    }

    private lateinit var headerAdapter: ArtworkDetailAdapter
    private lateinit var relatedAdapter: IAdapter
    private val relatedList = mutableListOf<IllustsBean>()

    override fun initLayout() {
        mLayoutID = R.layout.fragment_artwork_v3
    }

    override fun initView() {
        val illustId = safeArgs.illustId.toLong()

        headerAdapter = ArtworkDetailAdapter(this)
        relatedAdapter = IAdapter(relatedList, mContext)

        val concatAdapter = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
            headerAdapter,
            relatedAdapter
        )

        baseBind.recyclerView.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        baseBind.recyclerView.adapter = concatAdapter

        setupNavBar(illustId)
        setupScrollProgress()
        setupInfiniteScroll()
        handleSystemInsets()

        viewModel.headerItems.observe(viewLifecycleOwner) { items ->
            headerAdapter.submitItems(items)
        }

        viewModel.relatedIllusts.observe(viewLifecycleOwner) { illusts ->
            // Post to avoid notifying adapter during RecyclerView layout/scroll
            baseBind.recyclerView.post {
                val oldSize = relatedList.size
                if (illusts.size > oldSize) {
                    relatedList.addAll(illusts.subList(oldSize, illusts.size))
                    relatedAdapter.notifyItemRangeInserted(oldSize, illusts.size - oldSize)
                } else if (illusts.size != oldSize) {
                    relatedList.clear()
                    relatedList.addAll(illusts)
                    relatedAdapter.notifyDataSetChanged()
                }
            }
        }

        viewModel.isBookmarked.observe(viewLifecycleOwner) { bookmarked ->
            baseBind.navBookmark.setImageResource(
                if (bookmarked) R.drawable.ic_favorite_red_24dp
                else R.drawable.ic_favorite_grey_24dp
            )
        }
    }

    private fun setupInfiniteScroll() {
        baseBind.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as StaggeredGridLayoutManager
                val lastPositions = lm.findLastVisibleItemPositions(null)
                val lastVisible = lastPositions.maxOrNull() ?: return
                if (lastVisible >= lm.itemCount - 4 && viewModel.hasMoreRelated) {
                    viewModel.loadMoreRelated()
                }
            }
        })
    }

    private fun handleSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.navBar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top + 12.ppppx, v.paddingRight, v.paddingBottom)
            windowInsets
        }
    }

    private fun setupNavBar(illustId: Long) {
        baseBind.navBack.setOnClick { mActivity.finish() }
        baseBind.navMore.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            object : ShareIllust(mContext, illust) {
                override fun onPrepare() {}
            }.execute()
        }
        baseBind.navBookmark.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            PixivOperate.postLikeDefaultStarType(illust)
        }
    }

    private fun setupScrollProgress() {
        baseBind.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val offset = recyclerView.computeVerticalScrollOffset()
                val range = recyclerView.computeVerticalScrollRange() -
                        recyclerView.computeVerticalScrollExtent()
                baseBind.scrollProgressBar.scaleX = if (range > 0) offset.toFloat() / range else 0f
            }
        })
    }

    override fun vertical() {}

    companion object {
        @JvmStatic
        fun newInstance(illustId: Int): ArtworkV3Fragment {
            return ArtworkV3Fragment().apply {
                arguments = Bundle().apply { putInt("illust_id", illustId) }
            }
        }

        @JvmStatic
        fun newInstance(illustId: Long): ArtworkV3Fragment = newInstance(illustId.toInt())
    }
}
