package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.UActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.EAdapter;
import ceui.lisa.databinding.RecyUserEventBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentP extends FragmentList<ListIllustResponse, IllustsBean, RecyUserEventBinding> {

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getFollowUserIllust(sUserModel.getResponse().getAccess_token(),
                Shaft.sSettings.isTrendsForPrivate() ? FragmentLikeIllust.TYPE_PRIVATE : FragmentLikeIllust.TYPE_PUBLUC);
    }

    @Override
    public Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
    }


    @Override
    public void initRecyclerView() {
        super.initRecyclerView();
        baseBind.recyclerView.setBackgroundColor(getResources().getColor(R.color.white));
        baseBind.refreshLayout.setPrimaryColorsId(R.color.white);
    }

    @Override
    public RefreshFooter getFooter() {
        ClassicsFooter classicsFooter = new ClassicsFooter(mContext);
        classicsFooter.setPrimaryColorId(R.color.white);
        return classicsFooter;
    }

    @Override
    public void initAdapter() {
        mAdapter = new EAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 0) {
                    IllustChannel.get().setIllustList(allItems);
                    Intent intent = new Intent(mContext, ViewPagerActivity.class);
                    intent.putExtra("position", position);
                    startActivity(intent);
                } else if (viewType == 1) {
                    Intent intent = new Intent(mContext, UActivity.class);
                    intent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                    startActivity(intent);
                } else if (viewType == 2) {
                    if (allItems.get(position).getPage_count() == 1) {
                        IllustDownload.downloadIllust(allItems.get(position));
                    } else {
                        IllustDownload.downloadAllIllust(allItems.get(position));
                    }
                } else if (viewType == 3) {
                    PixivOperate.postLike(allItems.get(position), sUserModel, FragmentLikeIllust.TYPE_PUBLUC);
                }
            }
        });
    }
}
