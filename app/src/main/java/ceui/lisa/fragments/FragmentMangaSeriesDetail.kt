package ceui.lisa.fragments

import android.os.Bundle
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.IAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.databinding.FragmentMangaSeriesDetailBinding
import ceui.lisa.model.ListMangaSeriesDetail
import ceui.lisa.models.IllustsBean
import ceui.lisa.repo.MangaSeriesDetailRepo
import ceui.lisa.utils.Dev
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide

class FragmentMangaSeriesDetail: NetListFragment<FragmentBaseListBinding,
        ListMangaSeriesDetail, IllustsBean>() {

    private var seriesId: Int = 0

    override fun initBundle(bundle: Bundle) {
        seriesId = bundle.getInt(Params.ID)
        if (Dev.isDev) {
            seriesId = 45349
        }
    }

//    override fun initLayout() {
//        mLayoutID = R.layout.fragment_manga_series_detail
//    }

    companion object {
        @JvmStatic
        fun newInstance(seriesId: Int): FragmentMangaSeriesDetail {
            return FragmentMangaSeriesDetail().apply {
                arguments = Bundle().apply {
                    putInt(Params.ID, seriesId)
                }
            }
        }
    }
//
//    override fun initView() {
//        super.initView()
//        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0)
//    }
//
//    override fun onResponse(response: ListMangaSeriesDetail) {
//        Glide.with(mContext)
//                .load(GlideUtil.getArticle(response.illust_series_detail.cover_image_urls.medium))
//                .into(baseBind.coverImage)
//        baseBind.bigTitle.title = response.illust_series_detail.title
//        baseBind.toolbar.title = response.illust_series_detail.title
//    }

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return IAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return MangaSeriesDetailRepo(seriesId)
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_230)
    }

    override fun initRecyclerView() {
        staggerRecyclerView()
    }
}