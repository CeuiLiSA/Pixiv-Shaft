package ceui.lisa.fragments;

import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.KissAdapter;
import ceui.lisa.adapters.ViewHolder;
import ceui.lisa.databinding.FragmentListBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import ceui.lisa.view.LinearItemDecorationNoLR;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

public abstract class FragmentList<Response extends ListShow<ItemBean>, ItemBean, ItemView extends ViewDataBinding>
        extends BaseBindFragment<FragmentListBinding> {

    public static final int PAGE_SIZE = 20;
    Observable<Response> mApi;
    List<ItemBean> allItems = new ArrayList<>();
    String nextUrl = "";
    BaseAdapter<ItemBean, ItemView> mAdapter;
    public static long animateDuration = 400L;

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
        LandingAnimator landingAnimator = new LandingAnimator(new AnticipateOvershootInterpolator());
        landingAnimator.setAddDuration(animateDuration);
        landingAnimator.setRemoveDuration(animateDuration);
        landingAnimator.setMoveDuration(animateDuration);
        landingAnimator.setChangeDuration(animateDuration);
        baseBind.recyclerView.setItemAnimator(landingAnimator);

        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        baseBind.refreshLayout.setPrimaryColorsId(R.color.white);
        baseBind.refreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                getNextData();
            }
        });
        baseBind.refreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
        baseBind.refreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                mAdapter.clear();
                getFirstData();
            }
        });
        initRecyclerView();
        initAdapter();
        if (mAdapter != null) {
            baseBind.recyclerView.setAdapter(mAdapter);
        }
        if (showToolbar()) {
            baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mActivity.finish();
                }
            });
            baseBind.toolbar.setTitle(getToolbarTitle());
        } else {
            baseBind.toolbar.setVisibility(View.GONE);
        }
        if (autoRefresh()) {
            baseBind.refreshLayout.autoRefresh();
        }
    }

    public void initRecyclerView() {
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
    }

    public abstract Observable<Response> initApi();

    public abstract Observable<Response> initNextApi();

    public abstract void initAdapter();

    @Override
    public void getFirstData() {
        mApi = initApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<Response>() {
                        @Override
                        public void success(Response listIllustResponse) {
                            if (listIllustResponse.getList() != null && listIllustResponse.getList().size() != 0) {
                                int lastSize = allItems.size();
                                allItems.addAll(listIllustResponse.getList());
                                mAdapter.notifyItemRangeInserted(lastSize, allItems.size());
                            }
                            if (!TextUtils.isEmpty(listIllustResponse.getNextUrl())) {
                                nextUrl = listIllustResponse.getNextUrl();
                            } else {
                                baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                            }
                        }

                        @Override
                        public void must(boolean isSuccess) {
                            baseBind.refreshLayout.finishRefresh(isSuccess);
                        }
                    });
        }
    }

    @Override
    public void getNextData() {
        mApi = initNextApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<Response>() {
                        @Override
                        public void success(Response listIllustResponse) {
                            if (listIllustResponse.getList() != null && listIllustResponse.getList().size() != 0) {
                                int lastSize = allItems.size();
                                allItems.addAll(listIllustResponse.getList());
                                mAdapter.notifyItemRangeInserted(lastSize, allItems.size());
                            }
                            if (!TextUtils.isEmpty(listIllustResponse.getNextUrl())) {
                                nextUrl = listIllustResponse.getNextUrl();
                            } else {
                                baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                            }
                        }

                        @Override
                        public void must(boolean isSuccess) {
                            baseBind.refreshLayout.finishLoadMore(isSuccess);
                        }
                    });
        } else {
            baseBind.refreshLayout.finishLoadMore(false);
            baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        }
    }
}
