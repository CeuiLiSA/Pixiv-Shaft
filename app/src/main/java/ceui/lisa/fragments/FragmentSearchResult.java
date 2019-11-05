package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.GridLayoutManager;

import com.mxn.soul.flowingdrawer_core.ElasticDrawer;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.model.TempTokenResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.GridItemDecoration;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 搜索插画结果，
 */
public class FragmentSearchResult extends FragmentSList<ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    private String token = "";
    private String keyWord = "";
    private String starSize = "";
    private String sort = "date_desc";
    private String searchTarget = "partial_match_for_tags";
    private boolean isPopular = false;

    public static FragmentSearchResult newInstance(String keyWord) {
        return newInstance(keyWord, "date_desc", "partial_match_for_tags");
    }

    public static FragmentSearchResult newInstance(String keyWord, String sort) {
        return newInstance(keyWord, sort, "partial_match_for_tags");
    }

    public static FragmentSearchResult newInstance(String keyWord, String sort, String searchTarget) {
        FragmentSearchResult fragmentSearchResult = new FragmentSearchResult();
        fragmentSearchResult.keyWord = keyWord;
        fragmentSearchResult.sort = sort;
        fragmentSearchResult.searchTarget = searchTarget;
        fragmentSearchResult.starSize = Shaft.sSettings.getSearchFilter().contains("无限制") ?
                "" : " " + (Shaft.sSettings.getSearchFilter());
        return fragmentSearchResult;
    }

    @Override
    void initData() {
        ((TemplateFragmentActivity) getActivity()).setSupportActionBar(baseBind.toolbar);
        baseBind.toolbar.setTitle(getToolbarTitle());
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
        baseBind.searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (TextUtils.isEmpty(keyWord)) {
                    Common.showToast("请输入搜索内容");
                    return true;
                }
                Common.hideKeyboard(mActivity);
                mAdapter.clear();
                getFirstData();
                return true;
            }
        });

        baseBind.drawerlayout.setTouchMode(ElasticDrawer.TOUCH_MODE_BEZEL);
        token = sUserModel.getResponse().getAccess_token();
        FragmentFilter fragmentFilter = FragmentFilter.newInstance(new FragmentFilter.SearchFilter() {
            @Override
            public void onTagMatchChanged(String tagMatch) {
                searchTarget = tagMatch;
            }

            @Override
            public void onDateSortChanged(String dateSort) {
                sort = dateSort;
            }

            @Override
            public void onStarSizeChanged(String starSize) {
                FragmentSearchResult.this.starSize = starSize;
                baseBind.searchBox.setText(keyWord.contains("users入り") ? keyWord : keyWord + " " + FragmentSearchResult.this.starSize);
                baseBind.searchBox.setSelection(baseBind.searchBox.getText().length());
            }

            @Override
            public void onPopularChanged(boolean isPopular) {
                FragmentSearchResult.this.isPopular = isPopular;
            }

            @Override
            public void startSearch() {
                if (TextUtils.isEmpty(keyWord)) {
                    Common.showToast("请输入搜索内容");
                    return;
                }
                if (isPopular) {
                    getRankToken();
                } else {
                    baseBind.recyclerView.requestFocus();
                    baseBind.recyclerView.performClick();
                    mAdapter.clear();
                    getFirstData();
                }
            }

            @Override
            void closeDrawer() {
                baseBind.drawerlayout.closeMenu(true);
            }
        });
        FragmentManager fragmentManager = getChildFragmentManager();
        if (!fragmentFilter.isAdded()) {
            fragmentManager.beginTransaction()
                    .add(R.id.id_container_menu, fragmentFilter)
                    .commit();
        } else {
            fragmentManager.beginTransaction()
                    .show(fragmentFilter)
                    .commit();
        }

        baseBind.searchBox.setText(keyWord.contains("users入り") ? keyWord : keyWord + starSize);
        baseBind.searchBox.setSelection(baseBind.searchBox.getText().length());
        super.initData();
    }

    @Override
    public void initRecyclerView() {
        GridLayoutManager manager = new GridLayoutManager(mContext, 2);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(8.0f), true));
    }

    @Override
    public String getToolbarTitle() {
        return keyWord;
    }

    @Override
    public Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().searchIllust(token, baseBind.searchBox.getText().toString(), sort, searchTarget);
    }

    @Override
    public Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(token, nextUrl);
    }

    @Override
    public void initAdapter() {
        mAdapter = new IAdapter(allItems, mContext, true);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.illust_filter, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_filter) {
            Common.hideKeyboard(mActivity);
            if (baseBind.drawerlayout.isMenuVisible()) {
                baseBind.drawerlayout.closeMenu(true);
            } else {
                baseBind.drawerlayout.openMenu(true);
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void getRankToken() {
        if (sUserModel.getResponse().getUser().isIs_premium()) {
            sort = "popular_desc";
            starSize = "";
            mAdapter.clear();
            getFirstData();
        } else {
//            mAdapter.clear();
//            getFirstData();
//            Common.showToast("热度排序功能调试中");
            baseBind.progress.setVisibility(View.VISIBLE);
            Retro.getRankApi().getRankToken()
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<TempTokenResponse>() {
                        @Override
                        public void onNext(TempTokenResponse tempTokenResponse) {
                            if (tempTokenResponse != null) {
                                token = "Bearer " + tempTokenResponse.getToken();
                                sort = "popular_desc";
                                starSize = "";
                                mAdapter.clear();
                                getFirstData();
                            }
                            baseBind.progress.setVisibility(View.GONE);
                        }
                    });
        }
    }
}
