package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人创作的小说
 */
public class FragmentUserNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovel, NovelBean> {

    private int userID;
    private boolean showToolbar = false;

    public static FragmentUserNovel newInstance(int userID, boolean paramShowToolbar) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        FragmentUserNovel fragment = new FragmentUserNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        showToolbar = bundle.getBoolean(Params.FLAG);
    }

    @Override
    public NetControl<ListNovel> present() {
        return new NetControl<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                return Retro.getAppApi().getUserSubmitNovel(sUserModel
                        .getResponse().getAccess_token(), userID);
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(sUserModel.getResponse().getAccess_token(),
                        mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        return showToolbar ? "小说作品" : super.getToolbarTitle();
    }
}
