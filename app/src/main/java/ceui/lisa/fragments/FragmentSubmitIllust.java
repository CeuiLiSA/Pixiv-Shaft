package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.GridLayoutManager;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.http.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.view.GridItemDecoration;
import ceui.lisa.utils.IllustChannel;
import io.reactivex.Observable;

/**
 * 某人創作的插畫
 */
public class FragmentSubmitIllust extends AutoClipFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    private int userID;

    public static FragmentSubmitIllust newInstance(int userID){
        FragmentSubmitIllust fragmentRelatedIllust = new FragmentSubmitIllust();
        fragmentRelatedIllust.userID = userID;
        return fragmentRelatedIllust;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getUserSubmitIllust(mUserModel.getResponse().getAccess_token(), userID, "illust");
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        GridLayoutManager manager = new GridLayoutManager(mContext, 2);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(4.0f), false));
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext);
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
}
