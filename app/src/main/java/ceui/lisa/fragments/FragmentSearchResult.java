package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;

import com.mxn.soul.flowingdrawer_core.ElasticDrawer;
import com.mxn.soul.flowingdrawer_core.FlowingDrawer;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustAdapter;
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
import ceui.lisa.view.GridScrollChangeManager;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 搜索插画结果
 */
public class FragmentSearchResult extends AutoClipFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    private String token = "";
    private String keyWord = "";
    private String starSize = "";
    private String sort = "date_desc";
    private String searchTarget = "partial_match_for_tags";
    private FlowingDrawer mDrawer;
    private boolean isPopular = false;
    private FragmentFilter mFragmentFilter;
    private EditText mEditText;

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
                "" : (Shaft.sSettings.getSearchFilter() + "user");
        return fragmentSearchResult;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_search_result;
    }

    @Override
    View initView(View v) {
        super.initView(v);
        ((TemplateFragmentActivity) getActivity()).setSupportActionBar(mToolbar);
        mToolbar.setTitle(getToolbarTitle());
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().finish();
            }
        });
        mDrawer = v.findViewById(R.id.drawerlayout);
        mEditText = v.findViewById(R.id.search_box);
        mEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                Common.hideKeyboard(mActivity);
                getFirstData();
                return true;
            }
        });
        mDrawer.setTouchMode(ElasticDrawer.TOUCH_MODE_BEZEL);
        token = sUserModel.getResponse().getAccess_token();
        mFragmentFilter = FragmentFilter.newInstance(new FragmentFilter.SearchFilter() {
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
            }

            @Override
            public void onPopularChanged(boolean isPopular) {
                FragmentSearchResult.this.isPopular = isPopular;
            }

            @Override
            public void startSearch() {
                if (isPopular) {
                    getRankToken();
                } else {
                    getFirstData();
                }
            }

            @Override
            void closeDrawer() {
                mDrawer.closeMenu(true);
            }
        });
        FragmentManager fragmentManager = getChildFragmentManager();
        if (!mFragmentFilter.isAdded()) {
            fragmentManager.beginTransaction()
                    .add(R.id.id_container_menu, mFragmentFilter)
                    .commit();
        } else {
            fragmentManager.beginTransaction()
                    .show(mFragmentFilter)
                    .commit();
        }

        mEditText.setText(keyWord.contains("users入り") ? keyWord : keyWord + starSize);
        mEditText.setSelection(mEditText.getText().length());
        return v;
    }

    @Override
    void initRecyclerView() {
        GridScrollChangeManager manager = new GridScrollChangeManager(mContext, 2);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(4.0f), false));
    }

    @Override
    String getToolbarTitle() {
        return keyWord;
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().searchIllust(token, mEditText.getText().toString(), sort, searchTarget);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(token, nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext, mRecyclerView, mRefreshLayout);
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
            if (mDrawer.isMenuVisible()) {
                mDrawer.closeMenu(true);
            } else {
                mDrawer.openMenu(true);
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void getRankToken() {
        if (sUserModel.getResponse().getUser().isIs_premium()) {
            sort = "popular_desc";
            starSize = "";
            getFirstData();
        } else {
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
                                getFirstData();
                            }
                        }
                    });
        }
    }
}
