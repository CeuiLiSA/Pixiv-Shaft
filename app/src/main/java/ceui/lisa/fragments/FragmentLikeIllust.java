package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人收藏的插畫
 */
public class FragmentLikeIllust extends FragmentList<ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    public static final String TYPE_PUBLUC = "public";
    public static final String TYPE_PRIVATE = "private";
    private int userID;
    private String starType, tag = "";

    public static FragmentLikeIllust newInstance(int userID, String starType) {
        FragmentLikeIllust fragmentRelatedIllust = new FragmentLikeIllust();
        fragmentRelatedIllust.userID = userID;
        fragmentRelatedIllust.starType = starType;
        return fragmentRelatedIllust;
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager manager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public Observable<ListIllustResponse> initApi() {
        if (TextUtils.isEmpty(tag)) {
            return Retro.getAppApi().getUserLikeIllust(sUserModel.getResponse().getAccess_token(), userID, starType);
        } else {
            return Retro.getAppApi().getUserLikeIllust(sUserModel.getResponse().getAccess_token(), userID, starType, tag);
        }
    }

    @Override
    public Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    public void initAdapter() {
        mAdapter = new IAdapter(allItems, mContext);
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
        if (event.getReceiver().contains(starType)) {
            tag = (String) event.getObject();
            getFirstData();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
