package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.http.Retro;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.SpacesItemDecoration;
import ceui.lisa.view.ScrollChangeManager;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人收藏的插畫
 */
public class FragmentLikeIllust extends AutoClipFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

    private int userID;
    private String starType, tag = "";
    public static final String TYPE_PUBLUC = "public";
    public static final String TYPE_PRIVATE = "private";

    public static FragmentLikeIllust newInstance(int userID, String starType){
        FragmentLikeIllust fragmentRelatedIllust = new FragmentLikeIllust();
        fragmentRelatedIllust.userID = userID;
        fragmentRelatedIllust.starType = starType;
        return fragmentRelatedIllust;
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(4.0f)));
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        if(TextUtils.isEmpty(tag)){
            return Retro.getAppApi().getUserLikeIllust(sUserModel.getResponse().getAccess_token(), userID, starType);
        }else {
            return Retro.getAppApi().getUserLikeIllust(sUserModel.getResponse().getAccess_token(), userID, starType, tag);
        }
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        ScrollChangeManager layoutManager =
                new ScrollChangeManager(2, ScrollChangeManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
        mAdapter = new IllustStagAdapter(allItems, mContext, mRecyclerView, mRefreshLayout);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
        if(event.getReceiver().contains(starType)) {
            tag = (String) event.getObject();
            getFirstData();
        }
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);
        Common.showLog(className + "EVENTBUS 注册了");
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
        Common.showLog(className + "EVENTBUS 取消注册了");
    }
}
