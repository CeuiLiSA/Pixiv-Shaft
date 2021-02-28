package ceui.lisa.fragments

import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.UAdapter
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.databinding.RecyUserPreviewBinding
import ceui.lisa.model.ListUser
import ceui.lisa.models.UserPreviewsBean
import ceui.lisa.repo.NiceFriendRepo
import ceui.lisa.utils.Params

class FragmentNiceFriend : NetListFragment<FragmentBaseListBinding, ListUser, UserPreviewsBean>() {

    override fun repository(): RemoteRepo<ListUser> {
        return NiceFriendRepo(mActivity.intent.getIntExtra(Params.USER_ID, 0))
    }

    override fun adapter(): BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> {
        return UAdapter(allItems, mContext)
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_235)
    }
}
