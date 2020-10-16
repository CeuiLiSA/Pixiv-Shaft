package ceui.lisa.fragments

import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NovelSeriesAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListNovelSeries
import ceui.lisa.models.MangaSeriesItem
import ceui.lisa.models.NovelSeriesItem
import ceui.lisa.repo.NovelSeriesRepo
import ceui.lisa.utils.Params

class FragmentNovelSeries: NetListFragment<FragmentBaseListBinding, ListNovelSeries, NovelSeriesItem>() {

    override fun adapter(): BaseAdapter<*, out ViewDataBinding>? {
        return NovelSeriesAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return NovelSeriesRepo(mActivity.intent.getIntExtra(Params.USER_ID, 0))
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_257)
    }
}