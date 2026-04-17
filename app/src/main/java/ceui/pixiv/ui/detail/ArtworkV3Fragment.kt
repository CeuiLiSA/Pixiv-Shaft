package ceui.pixiv.ui.detail

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.adapters.IAdapter
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.dialogs.MuteDialog
import ceui.lisa.download.IllustDownload
import ceui.lisa.fragments.BaseFragment
import ceui.lisa.fragments.FragmentIllustArgs
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
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

    private var illustAdapter: ceui.lisa.adapters.IllustAdapter? = null
    private lateinit var headerAdapter: ArtworkDetailAdapter
    private lateinit var relatedAdapter: IAdapter
    private lateinit var loadingFooter: LoadingFooterAdapter
    private val relatedList = mutableListOf<IllustsBean>()

    override fun initLayout() {
        mLayoutID = R.layout.fragment_artwork_v3
    }

    override fun initView() {
        val illustId = safeArgs.illustId.toLong()

        headerAdapter = ArtworkDetailAdapter(this)

        // Apply themed scroll progress gradient
        val p = headerAdapter.palette
        baseBind.scrollProgressBar.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(p.scrollProgressStart, p.scrollProgressMid, p.scrollProgressEnd)
        )
        relatedAdapter = IAdapter(relatedList, mContext).apply {
            setUuid("artwork_v3_related_$illustId")
        }
        loadingFooter = LoadingFooterAdapter()

        val concatConfig = ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build()
        val concatAdapter = ConcatAdapter(
            concatConfig,
            headerAdapter,
            relatedAdapter,
            loadingFooter
        )

        val layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        baseBind.recyclerView.layoutManager = layoutManager
        baseBind.recyclerView.adapter = concatAdapter
        baseBind.recyclerView.addItemDecoration(RelatedOnlySpaceDecoration(4.ppppx))

        // Single scroll listener for both progress bar and infinite scroll
        baseBind.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Scroll progress
                val offset = recyclerView.computeVerticalScrollOffset()
                val range = recyclerView.computeVerticalScrollRange() -
                        recyclerView.computeVerticalScrollExtent()
                baseBind.scrollProgressBar.scaleX = if (range > 0) offset.toFloat() / range else 0f

                // Infinite scroll
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as StaggeredGridLayoutManager
                    val lastPositions = lm.findLastVisibleItemPositions(null)
                    val lastVisible = lastPositions.maxOrNull() ?: return
                    if (lastVisible >= lm.itemCount - 4 && viewModel.hasMoreRelated) {
                        viewModel.loadMoreRelated()
                    }
                }
            }
        })

        setupNavBar(illustId)
        handleSystemInsets()

        // When illust data arrives, insert IllustAdapter at the front for all pages
        ObjectPool.get<IllustsBean>(illustId).observe(viewLifecycleOwner) { illust ->
            if (illust != null && illustAdapter == null) {
                // Use 70% of screen height as max — not full screen, so single-page
                // images don't stretch to fill the entire viewport
                val maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
                illustAdapter = object : ceui.lisa.adapters.IllustAdapter(
                    mActivity, this@ArtworkV3Fragment, illust, maxHeight, false
                ) {
                    override fun onViewAttachedToWindow(holder: ceui.lisa.adapters.ViewHolder<ceui.lisa.databinding.RecyIllustDetailBinding>) {
                        super.onViewAttachedToWindow(holder)
                        val lp = holder.itemView.layoutParams
                        if (lp is StaggeredGridLayoutManager.LayoutParams) {
                            lp.isFullSpan = true
                        }
                    }
                }
                concatAdapter.addAdapter(0, illustAdapter!!)
            }
        }

        viewModel.headerItems.observe(viewLifecycleOwner) { items ->
            headerAdapter.submitItems(items)
        }

        viewModel.relatedIllusts.observe(viewLifecycleOwner) { illusts ->
            // Sync nextUrl so IAdapter's click handler can build PageData for VActivity
            relatedAdapter.setNextUrl(viewModel.relatedNextUrl)
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

        viewModel.isLoadingRelated.observe(viewLifecycleOwner) { loading ->
            if (loading) loadingFooter.show() else loadingFooter.hide()
        }

        viewModel.isBookmarked.observe(viewLifecycleOwner) { bookmarked ->
            baseBind.navBookmark.imageTintList = android.content.res.ColorStateList.valueOf(
                if (bookmarked) mContext.getColor(R.color.has_bookmarked)
                else android.graphics.Color.WHITE
            )
        }
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

        // Download button
        baseBind.navDownload.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            val baseAct = mActivity as? ceui.lisa.activities.BaseActivity<*>
            if (illust.page_count == 1) {
                IllustDownload.downloadIllustFirstPage(illust, baseAct)
            } else {
                IllustDownload.downloadIllustAllPages(illust, baseAct)
            }
            if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isIs_bookmarked) {
                PixivOperate.postLikeDefaultStarType(illust)
            }
        }

        // Bookmark button
        baseBind.navBookmark.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            PixivOperate.postLikeDefaultStarType(illust)
        }

        // More menu
        baseBind.navMore.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            val popup = PopupMenu(mContext, baseBind.navMore)
            popup.menuInflater.inflate(R.menu.share, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_share -> {
                        object : ShareIllust(mContext, illust) {
                            override fun onPrepare() {}
                        }.execute()
                        true
                    }
                    R.id.action_copy_link -> {
                        Common.copy(mContext, ShareIllust.URL_Head + illust.id)
                        true
                    }
                    R.id.action_dislike -> {
                        MuteDialog.newInstance(illust)
                            .show(childFragmentManager, "MuteDialog")
                        true
                    }
                    R.id.action_mute_illust -> {
                        PixivOperate.muteIllust(illust)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }


    override fun vertical() {}

    /**
     * Spacing decoration that only applies to non-fullSpan items (IAdapter's related cards).
     * Header items and loading footer are fullSpan and get zero offset.
     */
    private class RelatedOnlySpaceDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val lp = view.layoutParams
            if (lp !is StaggeredGridLayoutManager.LayoutParams || lp.isFullSpan) return

            outRect.bottom = space
            if (lp.spanIndex == 0) {
                outRect.left = space
                outRect.right = space / 2
            } else {
                outRect.left = space / 2
                outRect.right = space
            }
        }
    }

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
