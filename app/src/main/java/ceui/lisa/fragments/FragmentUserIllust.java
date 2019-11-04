package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人創作的插畫
 */
public class FragmentUserIllust extends FragmentList<ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    private int userID;

    public static FragmentUserIllust newInstance(int userID) {
        FragmentUserIllust fragmentRelatedIllust = new FragmentUserIllust();
        fragmentRelatedIllust.userID = userID;
        return fragmentRelatedIllust;
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getUserSubmitIllust(sUserModel.getResponse().getAccess_token(), userID, "illust");
    }

    @Override
    public Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager manager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
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
}
