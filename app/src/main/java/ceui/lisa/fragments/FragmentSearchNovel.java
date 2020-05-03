package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.databinding.ViewDataBinding;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.BaseCtrl;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Observable;

public class FragmentSearchNovel extends NetListFragment<FragmentBaseListBinding, ListNovel,
        NovelBean, RecyIllustStaggerBinding> {

    private String token = "";
    private String keyWord = "";
    private String starSize = "";
    private String sort = "date_desc";
    private String searchTarget = "partial_match_for_tags";
    private boolean isPopular = false;
    private boolean hasR18 = false;

    public static FragmentSearchNovel newInstance(String keyWord) {
        return newInstance(keyWord, "date_desc", "partial_match_for_tags");
    }

    public static FragmentSearchNovel newInstance(String keyWord, String sort) {
        return newInstance(keyWord, sort, "partial_match_for_tags");
    }

    public static FragmentSearchNovel newInstance(String keyWord, String sort,
                                                  String searchTarget) {
        Bundle args = new Bundle();
        args.putString(Params.KEY_WORD, keyWord);
        args.putString(Params.SORT_TYPE, sort);
        args.putString(Params.SEARCH_TYPE, searchTarget);
        args.putString(Params.STAR_SIZE, Shaft.sSettings.getSearchFilter().contains("无限制") ?
                "" : " " + (Shaft.sSettings.getSearchFilter()));
        FragmentSearchNovel fragment = new FragmentSearchNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        keyWord = bundle.getString(Params.KEY_WORD);
        sort = bundle.getString(Params.SORT_TYPE);
        searchTarget = bundle.getString(Params.SEARCH_TYPE);
        starSize = bundle.getString(Params.STAR_SIZE);
    }


    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }

    @Override
    public BaseCtrl present() {
        return new NetControl<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                PixivOperate.insertSearchHistory(keyWord, 0);
                return Retro.getAppApi().searchNovel(token, keyWord, sort, searchTarget);
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(token, mModel.getNextUrl());
            }
        };
    }


    @Override
    public boolean showToolbar() {
        return false;
    }
}
