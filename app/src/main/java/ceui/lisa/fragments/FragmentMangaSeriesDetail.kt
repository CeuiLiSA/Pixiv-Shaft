package ceui.lisa.fragments

import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.IAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.model.ListMangaOfSeries
import ceui.lisa.models.IllustsBean
import ceui.lisa.repo.MangaSeriesDetailRepo
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params

class FragmentMangaSeriesDetail :
    NetListFragment<FragmentBaseListBinding, ListMangaOfSeries, IllustsBean>() {

    private var seriesId: Int = 0

    override fun initBundle(bundle: Bundle) {
        seriesId = bundle.getInt(Params.ID)
    }

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

    override fun initView() {
        super.initView()
        baseBind.toolbar.inflateMenu(R.menu.local_save)
        baseBind.toolbar.setOnMenuItemClickListener(
            Toolbar.OnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_bookmark) {
                    val entity = FeatureEntity()
                    entity.uuid = seriesId.toString() + "漫画系列详情"
                    entity.dataType = "漫画系列详情"
                    entity.illustJson = Common.cutToJson(allItems)
                    // entity.userID = userID
                    entity.seriesId = seriesId
                    entity.dateTime = System.currentTimeMillis()
                    AppDatabase.getAppDatabase(mContext).downloadDao().insertFeature(entity)
                    Common.showToast("已收藏到精华")
                    return@OnMenuItemClickListener true
                }
                false
            }
        )
    }
}
