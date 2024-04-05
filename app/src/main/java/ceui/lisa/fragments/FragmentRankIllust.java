package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.RankIllustRepo;
import ceui.lisa.utils.Params;

/**
 * illust / manga 排行榜都用这个
 */
public class FragmentRankIllust extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    private static final String[] API_TITLES = new String[]{"day", "week",
            "month", "day_ai", "day_male", "day_female", "week_original", "week_rookie",
            "day_r18", "week_r18", "day_male_r18", "day_female_r18", "day_r18_ai", "week_r18g"};
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
        return new RankIllustRepo(isManga ? API_TITLES_MANGA[mIndex] : API_TITLES[mIndex], queryDate);
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
