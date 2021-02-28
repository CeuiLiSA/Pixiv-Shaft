package ceui.lisa.fragments

import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.IAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.repo.NiceFriendIllustRepo

class FragmentNiceFriendIllust :
    NetListFragment<FragmentBaseListBinding, ListIllust, IllustsBean>() {

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return IAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return NiceFriendIllustRepo()
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_274)
    }
}
