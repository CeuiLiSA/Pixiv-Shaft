package ceui.lisa.fragments;

import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import ceui.lisa.view.SpacesItemDecoration;
import ceui.lisa.viewmodel.BaseModel;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

public abstract class ListFragment<Layout extends ViewDataBinding, Item>
        extends BaseLazyFragment<Layout> {

    public static final long animateDuration = 400L;
    public static final int PAGE_SIZE = 20;
    protected RecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected ImageView noData;
    protected RelativeLayout emptyRela;
    protected BaseAdapter<?, ? extends ViewDataBinding> mAdapter;
    protected List<Item> allItems = null;
    protected BaseModel<Item> mModel;
    protected Toolbar mToolbar;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_base_list;
    }

    public abstract BaseAdapter<?, ? extends ViewDataBinding> adapter();

    public abstract BaseRepo repository();

    public void onAdapterPrepared() {

    }

    @Override
    public void initModel() {
        mModel = (BaseModel<Item>) new ViewModelProvider(this).get(modelClass());
        allItems = mModel.getContent();
        if (mModel.getBaseRepo() == null) {
            mModel.setBaseRepo(repository());
        }
    }

    public Class<? extends BaseModel> modelClass() {
        return BaseModel.class;
    }

    @Override
    public void initView() {

        mToolbar = rootView.findViewById(R.id.toolbar);
        if (mToolbar != null) {
            initToolbar(mToolbar);
        }

        mRecyclerView = rootView.findViewById(R.id.recyclerView);
        initRecyclerView();


        if (mRecyclerView.getLayoutManager() instanceof StaggeredGridLayoutManager) {
            //do nothing
        } else {
            //设置item动画
            mRecyclerView.setItemAnimator(animation());
        }


        mRefreshLayout = rootView.findViewById(R.id.refreshLayout);
        mRefreshLayout.setPrimaryColorsId(R.color.white);
        noData = rootView.findViewById(R.id.no_data);
        emptyRela = rootView.findViewById(R.id.no_data_rela);
        emptyRela.setOnClickListener(v -> {
            emptyRela.setVisibility(View.INVISIBLE);
            mRefreshLayout.autoRefresh();
        });
        mRefreshLayout.setRefreshHeader(mModel.getBaseRepo().enableRefresh() ?
                mModel.getBaseRepo().getHeader(mContext) : new FalsifyHeader(mContext));
        mRefreshLayout.setRefreshFooter(mModel.getBaseRepo().hasNext() ?
                mModel.getBaseRepo().getFooter(mContext) : new FalsifyFooter(mContext));

        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                try {
                    clear();
                    fresh();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                try {
                    if (mModel.getBaseRepo().hasNext()) {
                        loadMore();
                    } else {
                        mRefreshLayout.finishLoadMore();
                        mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        allItems = mModel.getContent();
        mAdapter = adapter();
        if (mAdapter != null) {
            mRecyclerView.setAdapter(mAdapter);
        }

        onAdapterPrepared();

        if (!isLazy()) {
            //进页面主动刷新
            if (autoRefresh() && !mModel.isLoaded()) {
                mRefreshLayout.autoRefresh();
            }
        }
    }

    @Override
    public void lazyData() {
        //进页面主动刷新
        if (autoRefresh() && !mModel.isLoaded()) {
            mRefreshLayout.autoRefresh();
        }
    }

    public void forceRefresh() {
        scrollToTop(() -> mRefreshLayout.autoRefresh());
    }

    public void scrollToTop(FeedBack feedBack) {
        try {
            mRecyclerView.smoothScrollToPosition(0);
            mRecyclerView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (feedBack != null) {
                        feedBack.doSomething();
                    }
                }
            }, animateDuration);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void scrollToTop() {
        scrollToTop(null);
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
            TextView title = toolbar.findViewById(R.id.toolbar_title);
            if (title != null) {
                title.setText(getToolbarTitle());
                title.setMovementMethod(ScrollingMovementMethod.getInstance());
                title.setHorizontallyScrolling(true);
            } else {
                toolbar.setTitle(getToolbarTitle());
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        } else {
            toolbar.setVisibility(View.GONE);
        }
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

    public int getCount() {
        return allItems == null ? 0 : allItems.size();
    }
}
