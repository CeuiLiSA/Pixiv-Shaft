package ceui.lisa.fragments

import androidx.recyclerview.widget.GridLayoutManager
import ceui.lisa.activities.Shaft.sUserModel
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.LiveAdapter
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.databinding.RecyItemLiveBinding
import ceui.lisa.http.Retro
import ceui.lisa.model.ListLive
import ceui.lisa.models.Live
import ceui.lisa.utils.DensityUtil
import ceui.lisa.view.TagItemDecoration
import ceui.lisa.view.TagItemDecoration2
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

    override fun getToolbarTitle(): String {
        return "人气直播"
    }

    override fun initRecyclerView() {
        val layoutManager = GridLayoutManager(context, 2)
        baseBind.recyclerView.layoutManager = layoutManager
        baseBind.recyclerView.addItemDecoration(TagItemDecoration2(DensityUtil.dp2px(12.0f)))
    }
}