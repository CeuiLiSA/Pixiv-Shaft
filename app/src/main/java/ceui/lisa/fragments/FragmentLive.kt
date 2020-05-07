package ceui.lisa.fragments

import ceui.lisa.activities.Shaft.sUserModel
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.LiveAdapter
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.databinding.RecyItemLiveBinding
import ceui.lisa.http.Retro
import ceui.lisa.model.ListLive
import ceui.lisa.models.Live
import io.reactivex.Observable

class FragmentLive : NetListFragment<FragmentBaseListBinding, ListLive,
        Live>() {

    override fun repository(): RemoteRepo<ListLive> {
        return object : RemoteRepo<ListLive>() {
            override fun initApi(): Observable<ListLive> {
                return Retro.getAppApi().getLiveList(sUserModel.response.access_token, "popular")
            }

            override fun initNextApi(): Observable<ListLive>? {
                return null
            }
        }
    }

    override fun adapter(): BaseAdapter<Live, RecyItemLiveBinding> {
        return LiveAdapter(allItems, mContext)
    }
}