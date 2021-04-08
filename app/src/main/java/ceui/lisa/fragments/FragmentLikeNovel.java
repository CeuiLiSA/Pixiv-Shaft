package ceui.lisa.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.FilterReceiver;
import ceui.lisa.repo.LikeNovelRepo;
import ceui.lisa.utils.Params;

/**
 * 某人收藏的小说
 */
public class FragmentLikeNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovel, NovelBean> {

    private int userID;
    private String starType, tag = "";
    private boolean showToolbar = false;
    private BroadcastReceiver filterReceiver;

    public static FragmentLikeNovel newInstance(int userID, String starType, boolean paramShowToolbar) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putString(Params.STAR_TYPE, starType);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        FragmentLikeNovel fragment = new FragmentLikeNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        starType = bundle.getString(Params.STAR_TYPE);
        showToolbar = bundle.getBoolean(Params.FLAG);
    }

    @Override
    public RemoteRepo<ListNovel> repository() {
        return new LikeNovelRepo(userID, starType, tag);
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext);
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
                        ((LikeNovelRepo) mRemoteRepo).setTag(tag);
                        baseBind.refreshLayout.autoRefresh();
                    }
                }
            }
        });
        intentFilter.addAction(Params.FILTER_NOVEL);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(filterReceiver, intentFilter);
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        return showToolbar ? getString(R.string.string_192) : super.getToolbarTitle();
    }
}
