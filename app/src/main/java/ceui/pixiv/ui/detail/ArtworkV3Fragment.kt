package ceui.pixiv.ui.detail

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.ShareIllust
import ceui.loxia.ObjectPool
import ceui.loxia.threadSafeArgs
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        headerAdapter.onCommentsVisible = { viewModel.loadComments() }
        headerAdapter.onAuthorWorksVisible = { viewModel.loadAuthorWorks() }
        headerAdapter.onRelatedVisible = { viewModel.loadRelated() }

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
        // Disable span-shuffle on gap — the header has many fullSpan items above a 2-column
        // grid; default MOVE_ITEMS_BETWEEN_SPANS causes jank as items re-layout during scroll.
        layoutManager.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        baseBind.recyclerView.layoutManager = layoutManager
        baseBind.recyclerView.adapter = concatAdapter
        baseBind.recyclerView.addItemDecoration(RelatedOnlySpaceDecoration(4.ppppx))
        // Header items are all fullSpan — DefaultItemAnimator's change animation on
        // notifyItemChanged (fired when ObjectPool pushes the updated UserBean after
        // returning from UActivity) scrambles SGLM's fullSpan tracking and makes the
        // Artist card snap flush with its upper neighbor. No useful animation to lose.
        baseBind.recyclerView.itemAnimator = null

        // Infinite scroll trigger near list end.
        baseBind.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private val lastVisiblePositions = IntArray(2)

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as StaggeredGridLayoutManager
                    lm.findLastVisibleItemPositions(lastVisiblePositions)
                    val lastVisible = maxOf(lastVisiblePositions[0], lastVisiblePositions[1])
                    if (lastVisible >= lm.itemCount - 4 && viewModel.hasMoreRelated) {
                        viewModel.loadMoreRelated()
                    }
                }
            }
        })

        // Hide/show floating action bar on scroll
        baseBind.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) hideFabBar() else if (dy < -8) showFabBar()
            }
        })

        setupNavBar(illustId)
        handleSystemInsets()

        // When illust data arrives, insert IllustAdapter at the front. For heavy
        // multi-page works (e.g. 50P manga) collapse all but the first few pages so
        // the reader can reach tags / comments / related works without a huge scroll;
        // the ExpandPagesAdapter card sits right after and reveals the rest on tap.
        ObjectPool.get<IllustsBean>(illustId).observe(viewLifecycleOwner) { illust ->
            if (illust != null && illustAdapter == null) {
                // Use 70% of screen height as max — not full screen, so single-page
                // images don't stretch to fill the entire viewport
                val maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
                val adapter = if (CollapsibleIllustAdapter.shouldCollapse(illust.page_count)) {
                    CollapsibleIllustAdapter(
                        mActivity,
                        this@ArtworkV3Fragment,
                        illust,
                        maxHeight,
                        false
                    )
                } else {
                    object : ceui.lisa.adapters.IllustAdapter(
                        mActivity, this@ArtworkV3Fragment, illust, maxHeight, false
                    ) {
                        override fun onViewAttachedToWindow(holder: ceui.lisa.adapters.ViewHolder<ceui.lisa.databinding.RecyIllustDetailBinding>) {
                            super.onViewAttachedToWindow(holder)
                            val lp = holder.itemView.layoutParams
                            if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
                                lp.isFullSpan = true
                                holder.itemView.layoutParams = lp
                            }
                        }
                    }
                }
                illustAdapter = adapter
                concatAdapter.addAdapter(0, adapter)
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
            baseBind.fabBookmark.imageTintList = android.content.res.ColorStateList.valueOf(
                if (bookmarked) mContext.getColor(R.color.has_bookmarked)
                else android.graphics.Color.WHITE
            )
        }
    }


    override fun onResume() {
        super.onResume()
        updateDownloadState(safeArgs.illustId.toLong())
    }

    private fun updateDownloadState(illustId: Long) {
        val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return
        val downloaded = Common.isIllustDownloaded(illust) ||
            ceui.lisa.database.AppDatabase.getAppDatabase(mContext).downloadDao()
                .hasDownloadRecordByIllustId(illust.id.toLong())
        baseBind.fabDownload.setImageResource(
            if (downloaded) R.drawable.ic_file_download_done_24dp else R.drawable.ic_file_download_black_24dp
        )
    }

    private var fabShown = true

    private fun hideFabBar() {
        if (!fabShown) return
        fabShown = false
        baseBind.fabBar.animate().translationY(baseBind.fabBar.height + 100f)
            .alpha(0f).setDuration(200).start()
    }

    private fun showFabBar() {
        if (fabShown) return
        fabShown = true
        baseBind.fabBar.animate().translationY(0f).alpha(1f).setDuration(200).start()
    }

    private fun handleSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top, v.paddingRight, v.paddingBottom)
            windowInsets
        }
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.fabBar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = insets.bottom + 24.ppppx
            v.layoutParams = lp
            windowInsets
        }
    }

    private fun setupNavBar(illustId: Long) {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }

        // Floating action bar
        baseBind.fabDownload.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            val baseAct = mActivity as? ceui.lisa.activities.BaseActivity<*>
            if (illust.page_count == 1) {
                IllustDownload.downloadIllustFirstPage(illust, baseAct)
            } else {
                IllustDownload.downloadIllustAllPages(illust, baseAct)
            }
            updateDownloadState(illustId)
            if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isIs_bookmarked) {
                PixivOperate.postLikeDefaultStarType(illust)
            }
        }
        updateDownloadState(illustId)

        baseBind.fabBookmark.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            // Optimistic UI: toggle icon immediately
            val willBookmark = !illust.isIs_bookmarked
            baseBind.fabBookmark.imageTintList = android.content.res.ColorStateList.valueOf(
                if (willBookmark) mContext.getColor(R.color.has_bookmarked) else android.graphics.Color.WHITE
            )
            PixivOperate.postLikeDefaultStarType(illust)
        }

        baseBind.fabBookmark.setOnLongClickListener {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnLongClickListener true
            val intent = Intent(mContext, ceui.lisa.activities.TemplateActivity::class.java).apply {
                putExtra(Params.ILLUST_ID, illust.id)
                putExtra(Params.DATA_TYPE, Params.TYPE_ILLUST)
                putExtra(Params.TAG_NAMES, illust.tagNames)
                putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "按标签收藏")
            }
            startActivity(intent)
            true
        }

        // More menu
        baseBind.navMore.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            showV3Menu {
                item(getString(R.string.share), R.drawable.ic_share_black_24dp) {
                    object : ShareIllust(mContext, illust) {
                        override fun onPrepare() {}
                    }.execute()
                }
                item(getString(R.string.string_454), R.drawable.ic_share_black_24dp) {
                    shareImage(illust)
                }
                item(getString(R.string.string_355_2), R.drawable.ic_baseline_launch_24) {
                    Common.copy(mContext, ShareIllust.URL_Head + illust.id)
                }
                item(getString(R.string.string_1), R.drawable.ic_baseline_settings_24) {
                    MuteDialog.newInstance(illust)
                        .show(this@ArtworkV3Fragment.childFragmentManager, "MuteDialog")
                }
                item(getString(R.string.string_355), R.drawable.ic_visibility_off_black_24dp) {
                    PixivOperate.muteIllust(illust)
                }
                item(getString(R.string.flag_post), R.drawable.ic_baseline_remove_red_eye_24) {
                    val intent = android.content.Intent(
                        mContext,
                        ceui.lisa.activities.TemplateActivity::class.java
                    )
                    intent.putExtra(
                        ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT,
                        "举报插画"
                    )
                    intent.putExtra(ceui.loxia.flag.FlagDescFragment.FlagObjectIdKey, illust.id)
                    intent.putExtra(
                        ceui.loxia.flag.FlagDescFragment.FlagObjectTypeKey,
                        ceui.lisa.models.ObjectSpec.POST
                    )
                    startActivity(intent)
                }
                item(getString(R.string.string_ai_upscale), R.drawable.ic_upscale_add_photo) {
                    ceui.pixiv.ui.upscale.ModelPickerDialog.pickOrUseDefault(
                        this@ArtworkV3Fragment.childFragmentManager
                    ) { model ->
                        // AI upscale requires IllustAiHelper
                    }
                }
            }
        }
    }


    private fun shareImage(illust: IllustsBean) {
        com.bumptech.glide.Glide.with(mContext)
            .asBitmap()
            .load(
                ceui.lisa.utils.GlideUrlChild(
                    ceui.lisa.download.IllustDownload.getUrl(
                        illust,
                        0,
                        ceui.lisa.utils.Params.IMAGE_RESOLUTION_LARGE
                    )
                )
            )
            .listener(object :
                com.bumptech.glide.request.RequestListener<android.graphics.Bitmap?> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<android.graphics.Bitmap?>,
                    isFirstResource: Boolean
                ) = false

                override fun onResourceReady(
                    resource: android.graphics.Bitmap,
                    model: Any,
                    target: com.bumptech.glide.request.target.Target<android.graphics.Bitmap?>?,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    // PNG compress + disk write are heavy — keep them off the main thread.
                    viewLifecycleOwner.lifecycleScope.launch {
                        val uri = withContext(Dispatchers.IO) {
                            Common.copyBitmapToImageCacheFolder(
                                resource,
                                illust.id.toString() + ".png"
                            )
                        } ?: return@launch
                        val shareIntent = android.content.Intent().apply {
                            action = android.content.Intent.ACTION_SEND
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            setDataAndType(uri, mContext.contentResolver.getType(uri))
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        }
                        startActivity(
                            android.content.Intent.createChooser(
                                shareIntent,
                                getString(R.string.share)
                            )
                        )
                    }
                    return true
                }
            }).submit()
    }

    override fun vertical() {}

    /**
     * Spacing decoration that only applies to non-fullSpan items (IAdapter's related cards).
     * Header items and loading footer are fullSpan and get zero offset.
     */
    private class RelatedOnlySpaceDecoration(private val space: Int) :
        RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
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
