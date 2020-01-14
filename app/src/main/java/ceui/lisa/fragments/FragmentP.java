package ceui.lisa.fragments;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

import ceui.lisa.R;
import ceui.lisa.activities.UActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.EAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserEventBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentP extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean, RecyUserEventBinding> {

    @Override
    public NetControl<ListIllust> present() {
        return new NetControl<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                return Retro.getAppApi().getFollowUserIllust(sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
            }

            @Override
            public RefreshFooter getFooter(Context context) {
                ClassicsFooter classicsFooter = new ClassicsFooter(context);
                classicsFooter.setPrimaryColorId(R.color.white);
                return classicsFooter;
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyUserEventBinding> adapter() {
        return new EAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 0) {
                    DataChannel.get().setIllustList(allItems);
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

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        super.initRecyclerView();
        baseBind.recyclerView.setBackgroundColor(getResources().getColor(R.color.white));
        baseBind.refreshLayout.setPrimaryColorsId(R.color.white);
    }
}
