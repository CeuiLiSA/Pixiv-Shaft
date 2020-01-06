package ceui.lisa.fragments

import ceui.lisa.activities.Shaft
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.UAdapter
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.databinding.RecyUserPreviewBinding
import ceui.lisa.http.Retro
import ceui.lisa.core.NetControl
import ceui.lisa.model.ListUserResponse
import ceui.lisa.models.UserPreviewsBean
import ceui.lisa.utils.Params
import io.reactivex.Observable

class FragmentNiceFriend : NetListFragment<FragmentBaseListBinding,
        ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding>() {

    override fun present(): NetControl<ListUserResponse> {
        return object : NetControl<ListUserResponse>() {
            override fun initApi(): Observable<ListUserResponse> {
                return Retro.getAppApi().getNiceFriend(Shaft.sUserModel.response.access_token,
                        mActivity.intent.getIntExtra(Params.USER_ID, 0))
            }

            override fun initNextApi(): Observable<ListUserResponse> {
                return Retro.getAppApi().getNextUser(Shaft.sUserModel.response.access_token, nextUrl)
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