package ceui.lisa.fragments

import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NovelMarkersAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListNovelMarkers
import ceui.lisa.models.MarkedNovelItem
import ceui.lisa.repo.NovelMarkersRepo

class FragmentNovelMarkers: NetListFragment<FragmentBaseListBinding, ListNovelMarkers, MarkedNovelItem>() {
    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NovelMarkersAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return NovelMarkersRepo()
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.core_string_novel_marker)
    }
}