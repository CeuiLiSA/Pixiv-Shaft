package ceui.lisa.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.IAdapterWithStar;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.feature.FeatureEntity;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.FilterReceiver;
import ceui.lisa.repo.LikeIllustRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

/**
 * 某人收藏的插畫
 */
public class FragmentLikeIllust extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    private int userID;
    private String starType, tag = "";
    private boolean showToolbar = false;
    private BroadcastReceiver filterReceiver;

    public static FragmentLikeIllust newInstance(int userID, String starType) {
        return newInstance(userID, starType, false);
    }

    public static FragmentLikeIllust newInstance(int userID, String starType,
                                                 boolean paramShowToolbar) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putString(Params.STAR_TYPE, starType);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        FragmentLikeIllust fragment = new FragmentLikeIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initView() {
        super.initView();
        baseBind.toolbar.inflateMenu(R.menu.local_save);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_bookmark) {
                    FeatureEntity entity = new FeatureEntity();
                    entity.setUuid(userID + "插画/漫画收藏");
                    entity.setShowToolbar(showToolbar);
                    entity.setDataType("插画/漫画收藏");
                    entity.setIllustJson(Common.cutToJson(allItems));
                    entity.setUserID(userID);
                    entity.setStarType(starType);
                    entity.setDateTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).downloadDao().insertFeature(entity);
                    Common.showToast("已收藏到精华");
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        starType = bundle.getString(Params.STAR_TYPE);
        showToolbar = bundle.getBoolean(Params.FLAG);
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new LikeIllustRepo(userID, starType, tag);
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        boolean isOwnPage = Shaft.sUserModel.getUser().getUserId() == userID;
        return new IAdapterWithStar(allItems, mContext).setHideStarIcon(
                isOwnPage && Shaft.sSettings.isHideStarButtonAtMyCollection()
        );
    }

    @Override
    public void onAdapterPrepared() {
        super.onAdapterPrepared();
        IntentFilter intentFilter = new IntentFilter();
        filterReceiver = new FilterReceiver(new BaseReceiver.CallBack() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    String type = bundle.getString(Params.STAR_TYPE);
                    if (starType.equals(type)) {
                        tag = bundle.getString(Params.CONTENT);
                        ((LikeIllustRepo) mRemoteRepo).setTag(tag);
                        baseBind.refreshLayout.autoRefresh();
                    }
                }
            }
        });
        intentFilter.addAction(Params.FILTER_ILLUST);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(filterReceiver, intentFilter);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (filterReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(filterReceiver);
        }
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        return showToolbar ? getString(R.string.string_164) : super.getToolbarTitle();
    }
}
