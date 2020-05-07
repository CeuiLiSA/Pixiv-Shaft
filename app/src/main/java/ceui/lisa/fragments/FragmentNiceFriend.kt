package ceui.lisa.fragments

import ceui.lisa.activities.Shaft
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.UAdapter
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.databinding.RecyUserPreviewBinding
import ceui.lisa.http.Retro
import ceui.lisa.model.ListUser
import ceui.lisa.models.UserPreviewsBean
import ceui.lisa.utils.Params
import io.reactivex.Observable

class FragmentNiceFriend : NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean>() {

    override fun repository(): RemoteRepo<ListUser> {
        return object : RemoteRepo<ListUser>() {
            override fun initApi(): Observable<ListUser> {
                return Retro.getAppApi().getNiceFriend(Shaft.sUserModel.response.access_token,
                        mActivity.intent.getIntExtra(Params.USER_ID, 0))
            }

            override fun initNextApi(): Observable<ListUser> {
                return Retro.getAppApi().getNextUser(
                        Shaft.sUserModel.response.access_token, mModel.nextUrl)
            }
        }
    }

    override fun adapter(): BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> {
        return UAdapter(allItems, mContext)
    }

    override fun getToolbarTitle(): String {
        return "好P友"
    }
}