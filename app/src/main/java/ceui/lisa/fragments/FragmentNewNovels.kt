package ceui.lisa.fragments

import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean
import ceui.lisa.repo.NewNovelRepo

class FragmentNewNovels : NetListFragment<FragmentBaseListBinding, ListNovel, NovelBean>() {

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return NewNovelRepo()
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_197)
    }
}
