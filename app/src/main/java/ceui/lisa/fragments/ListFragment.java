package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import ceui.lisa.view.SpacesItemDecoration;
import ceui.lisa.viewmodel.BaseModel;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

public abstract class ListFragment<Layout extends ViewDataBinding, Item>
        extends BaseFragment<Layout> {

    public static final long animateDuration = 400L;
    public static final int PAGE_SIZE = 20;
    protected RecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected ImageView noData;
    protected BaseAdapter mAdapter;
    protected List<Item> allItems = null;
    protected BaseModel<Item> mModel;
    protected Toolbar mToolbar;
    protected BaseRepo mBaseRepo;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_base_list;
    }

    public abstract BaseAdapter<?, ? extends ViewDataBinding> adapter();

    public abstract BaseRepo repository();

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        //获取viewmodel
        mModel = (BaseModel<Item>) new ViewModelProvider(this).get(modelClass());
        allItems = mModel.getContent().getValue();

        //为recyclerView设置Adapter
        mAdapter = adapter();
        if (mAdapter != null) {
            mRecyclerView.setAdapter(mAdapter);
        }

        onAdapterPrepared();

        //进页面主动刷新
        if (autoRefresh() && !mModel.isLoaded()) {
            mRefreshLayout.autoRefresh();
        }
    }

    public void onAdapterPrepared() {

    }

    public Class<? extends BaseModel> modelClass() {
        return BaseModel.class;
    }

    @Override
    public void initView(View view) {

        mToolbar = view.findViewById(R.id.toolbar);
        if (mToolbar != null) {
            initToolbar(mToolbar);
        }

        mRecyclerView = view.findViewById(R.id.recyclerView);
        initRecyclerView();


        if (mRecyclerView.getLayoutManager() instanceof StaggeredGridLayoutManager) {
            //do nothing
        } else {
            //设置item动画
            mRecyclerView.setItemAnimator(animation());
        }


        mRefreshLayout = view.findViewById(R.id.refreshLayout);
        noData = view.findViewById(R.id.no_data);
        noData.setOnClickListener(v -> {
            noData.setVisibility(View.INVISIBLE);
            mRefreshLayout.autoRefresh();
        });
        mBaseRepo = repository();
        mRefreshLayout.setRefreshHeader(mBaseRepo.enableRefresh() ?
                mBaseRepo.getHeader(mContext) : new FalsifyHeader(mContext));
        mRefreshLayout.setRefreshFooter(mBaseRepo.hasNext() ?
                mBaseRepo.getFooter(mContext) : new FalsifyFooter(mContext));

        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                clear();
                fresh();
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                if (mBaseRepo.hasNext()) {
                    loadMore();
                } else {
                    mRefreshLayout.finishLoadMore();
                    mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                }
            }
        });
    }

    public abstract void fresh();

    public abstract void loadMore();

    /**
     * 指定是否显示Toolbar
     *
     * @return default true
     */
    public boolean showToolbar() {
        return true;
    }

    /**
     * 指定Toolbar title
     *
     * @return title
     */
    public String getToolbarTitle() {
        return "";
    }


    public void initRecyclerView() {
        verticalRecyclerView();
    }

    public void verticalRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
    }

    protected void staggerRecyclerView() {
        StaggeredGridLayoutManager manager = new StaggeredGridLayoutManager(
                2, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(
                DensityUtil.dp2px(8.0f)));
    }

    /**
     * 决定刚进入页面是否直接刷新，一般都是直接刷新，但是FragmentHotTag，不要直接刷新
     *
     * @return default true
     */
    public boolean autoRefresh() {
        return true;
    }

    public void initToolbar(Toolbar toolbar) {
        if (showToolbar()) {
            toolbar.setVisibility(View.VISIBLE);
        } else {
            toolbar.setVisibility(View.GONE);
        }
        toolbar.setTitle(getToolbarTitle());
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
    }

    public void beforeFirstLoad(List<Item> items) {

    }

    public void beforeNextLoad(List<Item> items) {

    }

    public void onFirstLoaded(List<Item> items) {

    }

    public void onNextLoaded(List<Item> items) {

    }

    public void clear() {
        if (mAdapter != null) {
            mAdapter.clear();
        }
    }

    public void clearAndRefresh() {
        clear();
        if (mRefreshLayout != null) {
            mRefreshLayout.autoRefresh();
        }
    }

    public BaseItemAnimator animation() {
        //设置item动画
        BaseItemAnimator baseItemAnimator = new LandingAnimator();
        baseItemAnimator.setAddDuration(animateDuration);
        baseItemAnimator.setRemoveDuration(animateDuration);
        baseItemAnimator.setMoveDuration(animateDuration);
        baseItemAnimator.setChangeDuration(animateDuration);
        return baseItemAnimator;
    }

    public int getStartSize() {
        return allItems.size() + mAdapter.headerSize();
    }

    public void nowRefresh() {
        mRecyclerView.smoothScrollToPosition(0);
        mRefreshLayout.autoRefresh();
    }

    public List<Item> getContent() {
        if (mModel == null) {
            return new ArrayList<>();
        }
        return mModel.getContent().getValue();
    }
}
