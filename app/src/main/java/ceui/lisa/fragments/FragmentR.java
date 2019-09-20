package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.ScrollChangeManager;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

public class FragmentR extends FragmentList<ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRecmdIllust(Shaft.sUserModel.getResponse().getAccess_token(), true);
    }

    @Override
    public Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }

    @Override
    public void initAdapter() {
        mAdapter = new IAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener((v, position, viewType) -> {
            IllustChannel.get().setIllustList(allItems);
            Intent intent = new Intent(mContext, ViewPagerActivity.class);
            intent.putExtra("position", position);
            startActivity(intent);
        });
    }

    @Override
    public void firstSuccess() {
        //向FragmentCenter发送数据
        if(mResponse != null) {
            Channel<List<IllustsBean>> channel = new Channel<>();
            channel.setReceiver("FragmentRankHorizontal");
            channel.setObject(mResponse.getRanking_illusts());
            EventBus.getDefault().post(channel);
            Common.showLog(className + "EVENTBUS 发送了消息");
        }
    }
}
