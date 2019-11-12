package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AnticipateOvershootInterpolator;

import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.blankj.utilcode.util.NetworkUtils;
import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.databinding.FragmentListBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

public abstract class FragmentList<Response extends ListShow<ItemBean>, ItemBean, ItemView extends ViewDataBinding>
        extends BaseBindFragment<FragmentListBinding> {

    public static long animateDuration = 400L;
    Observable<Response> mApi;
    Response mResponse;
    List<ItemBean> allItems = new ArrayList<>();
    String nextUrl = "";
    BaseAdapter<ItemBean, ItemView> mAdapter;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_list;
    }

    public boolean showToolbar() {
        return true;
    }

    public String getToolbarTitle() {
        return "";
    }

    public boolean autoRefresh() {
        return true;
    }

    @Override
    void initData() {
        baseBind.refreshLayout.setPrimaryColorsId(R.color.white);
        baseBind.refreshLayout.setOnLoadMoreListener(refreshLayout -> getNextData());
        baseBind.refreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
        baseBind.refreshLayout.setOnRefreshListener(refreshLayout -> {
            mAdapter.clear();
            getFirstData();
        });
        baseBind.result.setOnClickListener(view -> {
            mAdapter.clear();
            getFirstData();
        });
        initRecyclerView();
        initAdapter();
        if (mAdapter != null) {
            baseBind.recyclerView.setAdapter(mAdapter);
        }
        if (!(baseBind.recyclerView.getLayoutManager() instanceof StaggeredGridLayoutManager)) {
            BaseItemAnimator baseItemAnimator = new LandingAnimator(new AnticipateOvershootInterpolator());
            baseItemAnimator.setAddDuration(animateDuration);
            baseItemAnimator.setRemoveDuration(animateDuration);
            baseItemAnimator.setMoveDuration(animateDuration);
            baseItemAnimator.setChangeDuration(animateDuration);
            baseBind.recyclerView.setItemAnimator(baseItemAnimator);
        }
        if (showToolbar()) {
            baseBind.toolbar.setNavigationOnClickListener(view -> mActivity.finish());
            baseBind.toolbar.setTitle(getToolbarTitle());
        } else {
            baseBind.toolbar.setVisibility(View.GONE);
        }
        if (autoRefresh()) {
            baseBind.refreshLayout.autoRefresh();
        }
    }

    public abstract Observable<Response> initApi();

    public abstract Observable<Response> initNextApi();

    public abstract void initAdapter();

    @Override
    public void getFirstData() {
        if(NetworkUtils.isConnected()) {
            mApi = initApi();
            if (mApi != null) {
                mApi.subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new NullCtrl<Response>() {
                            @Override
                            public void success(Response response) {
                                mResponse = response;
                                firstSuccess();
                                if (response.getList() != null && response.getList().size() != 0) {
                                    int lastSize = allItems.size();
                                    allItems.addAll(response.getList());
                                    mAdapter.notifyItemRangeInserted(lastSize, response.getList().size());
                                }
                                nextUrl = response.getNextUrl();
                                if (!TextUtils.isEmpty(nextUrl)) {
                                    baseBind.refreshLayout.setRefreshFooter(getFooter());
                                } else {
                                    baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                                }
                            }

                            @Override
                            public void must(boolean isSuccess) {
                                baseBind.refreshLayout.finishRefresh(isSuccess);
                                baseBind.result.setVisibility(View.GONE);
                            }
                        });
            } else {
                if (className.equals("FragmentR ")) {
                    showDataBase();
                }
            }
        } else {
            baseBind.result.setVisibility(View.VISIBLE);
            baseBind.result.setImageResource(R.mipmap.load_error);
            baseBind.refreshLayout.finishRefresh(false);
            Common.showToast("无网络连接");
        }
    }

    public void showDataBase() {
    }

    @Override
    public void getNextData() {
        if(NetworkUtils.isConnected()) {
            mApi = initNextApi();
            if (mApi != null && !TextUtils.isEmpty(nextUrl)) {
                mApi.subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new NullCtrl<Response>() {
                            @Override
                            public void success(Response response) {
                                mResponse = response;
                                if (response.getList() != null && response.getList().size() != 0) {
                                    int lastSize = allItems.size();
                                    allItems.addAll(response.getList());
                                    mAdapter.notifyItemRangeInserted(lastSize, response.getList().size());
                                }
                                nextUrl = response.getNextUrl();
                            }

                            @Override
                            public void must(boolean isSuccess) {
                                baseBind.refreshLayout.finishLoadMore(isSuccess);
                                baseBind.result.setVisibility(View.GONE);
                            }
                        });
            } else {
                Common.showToast("没有更多数据啦");
                baseBind.refreshLayout.finishLoadMore(true);
            }
        } else {
            baseBind.refreshLayout.finishLoadMore(false);
            Common.showToast("无网络连接");
        }
    }

    public RefreshFooter getFooter() {
        return new ClassicsFooter(mContext);
    }

    public void initRecyclerView() {
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
    }

    public void firstSuccess() {
    }
}
