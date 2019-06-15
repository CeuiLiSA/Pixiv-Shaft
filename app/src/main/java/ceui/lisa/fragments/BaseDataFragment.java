//package ceui.lisa.fragments;
//
//import android.support.v7.widget.LinearLayoutManager;
//import android.support.v7.widget.RecyclerView;
//import android.support.v7.widget.Toolbar;
//import android.view.View;
//import android.widget.ImageView;
//import android.widget.ProgressBar;
//
//import com.scwang.smartrefresh.header.DeliveryHeader;
//import com.scwang.smartrefresh.layout.api.RefreshLayout;
//
//import java.util.ArrayList;
//import java.util.List;
//
//import ceui.lisa.R;
//import ceui.lisa.interfaces.Callback;
//import ceui.lisa.utils.Common;
//import io.reactivex.Observable;
//
//public abstract class BaseDataFragment<Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>,
//        ListItem> extends BaseFragment {
//
//    protected Adapter mAdapter;
//    protected List<ListItem> allItems = new ArrayList<>();
//    public static final int PAGE_SIZE = 20;
//    protected RecyclerView mRecyclerView;
//    protected RefreshLayout mRefreshLayout;
//    protected ProgressBar mProgressBar;
//    protected Toolbar mToolbar;
//    protected ImageView noData;
//    protected OnPrepared<ListItem> mOnPrepared;
//
//
//    public interface OnPrepared<T> {
//        void showData(List<T> data);
//    }
//
//    @Override
//    void initLayout() {
//        mLayoutID = R.layout.fragment_illust_list;
//    }
//
//    @Override
//    View initView(View v) {
//        mToolbar = v.findViewById(R.id.toolbar);
//        if(showToolbar()){
//            mToolbar.setNavigationOnClickListener(view -> getActivity().finish());
//            mToolbar.setTitle(getToolbarTitle());
//        } else {
//            if(mToolbar != null) {
//                mToolbar.setVisibility(View.GONE);
//            }
//        }
//        if(hasNext()) {
//            mRefreshLayout.setOnLoadMoreListener(layout -> getNextData());
//        }
//        mProgressBar = v.findViewById(R.id.progress);
//        noData = v.findViewById(R.id.no_data);
//        noData.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                getFirstData();
//            }
//        });
//        mRecyclerView = v.findViewById(R.id.recyclerView);
//        LinearLayoutManager manager = new LinearLayoutManager(mContext);
//        mRecyclerView.setLayoutManager(manager);
//        mRecyclerView.setHasFixedSize(true);
//        mRefreshLayout = v.findViewById(R.id.refreshLayout);
//        mRefreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
//        mRefreshLayout.setOnRefreshListener(layout -> getFirstData());
//        mRefreshLayout.setEnableLoadMore(false);
//
//        return v;
//    }
//
//    String getToolbarTitle(){
//        return " ";
//    }
//
//    boolean hasNext() {
//        return false;
//    }
//
//    boolean showToolbar() {
//        return true;
//    }
//
//    @Override
//    void initData() {
//        getFirstData();
//    }
//
//    public Callback<List<ListItem>> mCallback;
//
//    abstract List<ListItem> initList();
//
//    public List<ListItem> initNextList(){
//        return null;
//    }
//
//    public void getFirstData(){
//        Common.showLog(className + "nodata 点击了一次");
//        noData.setVisibility(View.INVISIBLE);
//        allItems.clear();
//        List<ListItem> tempList = initList();
//        if (tempList != null) {
//            allItems.addAll(tempList);
//        }
//        initAdapter();
//        if(allItems.size() == 0){
//            noData.setVisibility(View.VISIBLE);
//            mRecyclerView.setVisibility(View.INVISIBLE);
//        }else {
//            mRecyclerView.setVisibility(View.VISIBLE);
//            noData.setVisibility(View.INVISIBLE);
//            mRecyclerView.setAdapter(mAdapter);
//        }
//        mProgressBar.setVisibility(View.INVISIBLE);
//        mRefreshLayout.finishRefresh(true);
//    }
//
//    public void getNextData() {
//        List<ListItem> tempList = initNextList();
//        if (tempList == null) {
//            return;
//        }
//        allItems.addAll(tempList);
//        int lastSize = allItems.size();
//        mRefreshLayout.finishLoadMore(true);
//        if (mAdapter != null) {
//            mAdapter.notifyItemRangeChanged(lastSize, tempList.size());
//        }
//    }
//
//    abstract void initAdapter();
//}
