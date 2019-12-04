package ceui.lisa.fragments;

import android.content.Context;
import android.os.Bundle;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshHeader;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.core.NetControl;
import ceui.lisa.model.ListNovelResponse;
import ceui.lisa.model.NovelBean;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;


public class FragmentRankNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovelResponse, NovelBean, RecyNovelBinding> {

    private static final String[] API_TITLES = new String[]{"day", "week",
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
    public NetControl<ListNovelResponse> present() {
        return new NetControl<ListNovelResponse>() {
            @Override
            public Observable<ListNovelResponse> initApi() {
                return Retro.getAppApi().getRankNovel(Shaft.sUserModel.getResponse().getAccess_token(),
                        API_TITLES[mIndex], queryDate);
            }

            @Override
            public Observable<ListNovelResponse> initNextApi() {
                return Retro.getAppApi().getNextNovel(sUserModel.getResponse().getAccess_token(), nextUrl);
            }

            @Override
            public RefreshHeader getHeader(Context context) {
                return new MaterialHeader(context);
            }
        };
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }
}
