package ceui.lisa.fragments

import androidx.databinding.ViewDataBinding
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.WatchlistNovelAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListWatchlistNovel
import ceui.lisa.models.WatchlistNovelItem
import ceui.lisa.repo.WatchlistNovelRepo

class FragmentWatchlistNovel:
    NetListFragment<FragmentBaseListBinding, ListWatchlistNovel, WatchlistNovelItem>() {
    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return WatchlistNovelAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return WatchlistNovelRepo()
    }

    override fun showToolbar(): Boolean {
        return false
    }
}