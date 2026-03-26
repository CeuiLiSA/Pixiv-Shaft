package ceui.lisa.fragments

import androidx.databinding.ViewDataBinding
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.WatchlistMangaAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListWatchlistManga
import ceui.lisa.models.WatchlistMangaItem
import ceui.lisa.repo.WatchlistMangaRepo

class FragmentWatchlistManga:
    NetListFragment<FragmentBaseListBinding, ListWatchlistManga, WatchlistMangaItem>() {
    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return WatchlistMangaAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return WatchlistMangaRepo()
    }

    override fun showToolbar(): Boolean {
        return false
    }
}
