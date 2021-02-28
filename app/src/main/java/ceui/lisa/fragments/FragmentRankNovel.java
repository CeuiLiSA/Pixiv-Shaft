package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.repo.RankNovelRepo;
import ceui.lisa.utils.Params;


public class FragmentRankNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovel, NovelBean> {

    private static final String[] API_TITLES_VALUES = new String[]{"day", "week",
            "day_male", "day_female", "week_rookie", "day_r18"};
    private int mIndex = -1;
    private String queryDate = "";

    public static FragmentRankNovel newInstance(int index, String date) {
        Bundle args = new Bundle();
        args.putInt(Params.INDEX, index);
        args.putString(Params.DAY, date);
        FragmentRankNovel fragment = new FragmentRankNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mIndex = bundle.getInt(Params.INDEX);
        queryDate = bundle.getString(Params.DAY);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public RemoteRepo<ListNovel> repository() {
        return new RankNovelRepo(API_TITLES_VALUES[mIndex], queryDate);
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }
}
