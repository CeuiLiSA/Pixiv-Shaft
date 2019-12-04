package ceui.lisa.fragments;

import android.content.Context;
import android.os.Bundle;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshHeader;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.core.NetControl;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * illust / manga 排行榜都用这个类
 */
public class FragmentRank extends NetListFragment<FragmentBaseListBinding,
        ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    private static final String[] API_TITLES = new String[]{"day", "week",
            "month", "day_male", "day_female", "week_original", "week_rookie",
            "day_r18"};
    private static final String[] API_TITLES_MANGA = new String[]{"day_manga",
            "week_manga", "month_manga", "week_rookie_manga", "day_r18_manga"};


    private int mIndex = -1;
    private boolean isManga = false;
    private String queryDate = "";

    public static FragmentRank newInstance(int index, String date, boolean isManga) {
        Bundle args = new Bundle();
        args.putInt(Params.INDEX, index);
        args.putBoolean(Params.MANGA, isManga);
        args.putString(Params.DAY, date);
        FragmentRank fragment = new FragmentRank();
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
    public NetControl<ListIllustResponse> present() {
        return new NetControl<ListIllustResponse>() {
            @Override
            public Observable<ListIllustResponse> initApi() {
                return Retro.getAppApi().getRank(Shaft.sUserModel.getResponse().getAccess_token(),
                        isManga ? API_TITLES_MANGA[mIndex] : API_TITLES[mIndex], queryDate);
            }

            @Override
            public Observable<ListIllustResponse> initNextApi() {
                return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
            }

            @Override
            public RefreshHeader getHeader(Context context) {
                return new MaterialHeader(context);
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }
}
