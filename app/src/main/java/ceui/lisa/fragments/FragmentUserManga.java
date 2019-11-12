package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人創作的漫画
 */
public class FragmentUserManga extends NetListFragment<FragmentBaseListBinding,
        ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    private int userID;
    private boolean showToolbar = false;

    public static FragmentUserManga newInstance(int userID) {
        FragmentUserManga fragmentRelatedIllust = new FragmentUserManga();
        fragmentRelatedIllust.userID = userID;
        return fragmentRelatedIllust;
    }

    public static FragmentUserManga newInstance(int userID, boolean paramShowToolbar) {
        FragmentUserManga fragmentRelatedIllust = new FragmentUserManga();
        fragmentRelatedIllust.userID = userID;
        fragmentRelatedIllust.showToolbar = paramShowToolbar;
        return fragmentRelatedIllust;
    }

    @Override
    public NetControl<ListIllustResponse> present() {
        return new NetControl<ListIllustResponse>() {
            @Override
            public Observable<ListIllustResponse> initApi() {
                return Retro.getAppApi().getUserSubmitIllust(sUserModel.getResponse().getAccess_token(), userID, "manga");
            }

            @Override
            public Observable<ListIllustResponse> initNextApi() {
                return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        if (showToolbar) {
            return "漫画作品";
        } else {
            return super.getToolbarTitle();
        }
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager manager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }
}
