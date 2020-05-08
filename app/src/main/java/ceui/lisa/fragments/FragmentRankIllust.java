package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * illust / manga 排行榜都用这个
 */
public class FragmentRankIllust extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    private static final String[] API_TITLES = new String[]{"day", "week",
            "month", "day_male", "day_female", "week_original", "week_rookie",
            "day_r18"};
    private static final String[] API_TITLES_MANGA = new String[]{"day_manga",
            "week_manga", "month_manga", "week_rookie_manga", "day_r18_manga"};


    private int mIndex = -1;
    private boolean isManga = false;
    private String queryDate = "";

    public static FragmentRankIllust newInstance(int index, String date, boolean isManga) {
        Bundle args = new Bundle();
        args.putInt(Params.INDEX, index);
        args.putBoolean(Params.MANGA, isManga);
        args.putString(Params.DAY, date);
        FragmentRankIllust fragment = new FragmentRankIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mIndex = bundle.getInt(Params.INDEX);
        queryDate = bundle.getString(Params.DAY);
        isManga = bundle.getBoolean(Params.MANGA);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new RemoteRepo<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                return Retro.getAppApi().getRank(Shaft.sUserModel.getResponse().getAccess_token(),
                        isManga ? API_TITLES_MANGA[mIndex] : API_TITLES[mIndex], queryDate);
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(),
                        mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}
