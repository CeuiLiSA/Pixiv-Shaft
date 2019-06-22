//package ceui.lisa.fragments;
//
//import android.os.Bundle;
//import android.support.v7.widget.LinearLayoutManager;
//import android.support.v7.widget.RecyclerView;
//import android.support.v7.widget.Toolbar;
//import android.view.View;
//import android.widget.ProgressBar;
//
//import com.google.gson.Gson;
//import com.scwang.smartrefresh.header.DeliveryHeader;
//import com.scwang.smartrefresh.layout.api.RefreshLayout;
//import com.scwang.smartrefresh.layout.util.DensityUtil;
//
//import org.greenrobot.eventbus.EventBus;
//import org.greenrobot.eventbus.Subscribe;
//import org.greenrobot.eventbus.ThreadMode;
//
//import java.util.ArrayList;
//import java.util.List;
//
//import ceui.lisa.R;
//import ceui.lisa.adapters.DownlistAdapter;
//import ceui.lisa.adapters.SpringRecyclerView;
//import ceui.lisa.database.AppDatabase;
//import ceui.lisa.database.DownloadEntity;
//import ceui.lisa.interfaces.OnItemClickListener;
//import ceui.lisa.model.IllustsBean;
//import ceui.lisa.utils.Channel;
//import ceui.lisa.utils.Common;
//import ceui.lisa.view.LinearItemDecoration;
//import io.reactivex.Observable;
//import io.reactivex.ObservableOnSubscribe;
//import io.reactivex.Observer;
//import io.reactivex.android.schedulers.AndroidSchedulers;
//import io.reactivex.disposables.Disposable;
//import io.reactivex.schedulers.Schedulers;
//
//import static ceui.lisa.fragments.BaseListFragment.PAGE_SIZE;
//
//public class FragmentHasDownload extends BaseFragment {
//
//    protected DownlistAdapter mAdapter;
//    protected RecyclerView mRecyclerView;
//    protected RefreshLayout mRefreshLayout;
//    protected List<DownloadEntity> allItems = new ArrayList<>();
//    protected List<IllustsBean> allIllusts = new ArrayList<>();
//    protected ProgressBar mProgressBar;
//    protected Toolbar mToolbar;
//    protected int nowIndex = 0;
//    protected BaseDataFragment.OnPrepared<DownloadEntity> mOnPrepared;
//
//    @Override
//    void initLayout() {
//        mLayoutID = R.layout.fragment_illust_list;
//    }
//
//    @Override
//    View initView(View v) {
//        mOnPrepared = new BaseDataFragment.OnPrepared<DownloadEntity>() {
//            @Override
//            public void showData(List<DownloadEntity> data) {
//                mAdapter = new DownlistAdapter(allItems, mContext);
//                mAdapter.setOnItemClickListener(new OnItemClickListener() {
//                    @Override
//                    public void onItemClick(View v, int position, int viewType) {
////                    IllustChannel.get().setIllustList(allIllusts);
////                    Intent intent = new Intent(mContext, ViewPagerActivity.class);
////                    intent.putExtra("position", position);
////                    startActivity(intent);
//
//
//                        Common.showLog(className + position);
//                    }
//                });
//                mProgressBar.setVisibility(View.INVISIBLE);
//                mRecyclerView.setAdapter(mAdapter);
//                mRefreshLayout.finishRefresh(true);
//            }
//        };
//        mToolbar = v.findViewById(R.id.toolbar);
//        mToolbar.setVisibility(View.GONE);
//        mProgressBar = v.findViewById(R.id.progress);
//        mRecyclerView = v.findViewById(R.id.recyclerView);
//        LinearLayoutManager manager = new LinearLayoutManager(mContext);
//        mRecyclerView.setLayoutManager(manager);
//        mRecyclerView.setHasFixedSize(true);
//        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(8.0f)));
//        mRefreshLayout = v.findViewById(R.id.refreshLayout);
//        mRefreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
//        mRefreshLayout.setOnRefreshListener(layout -> getFirstData());
//        mRefreshLayout.setOnLoadMoreListener(layout -> getNextData());
//        mRefreshLayout.setEnableLoadMore(false);
//        return v;
//    }
//
//
//
//    private void getFirstData() {
//        mProgressBar.setVisibility(View.VISIBLE);
//        allItems.clear();
//        nowIndex = 0;
//        Observable.create((ObservableOnSubscribe<String>) emitter -> {
//            emitter.onNext("开始查询数据库");
//            List<DownloadEntity> temp = AppDatabase.getAppDatabase(mContext).downloadDao().getAll(PAGE_SIZE, nowIndex);
//            nowIndex += temp.size();
//            allItems.addAll(temp);
//            emitter.onNext("开始转换数据类型");
//            Thread.sleep(500);
//            Gson gson = new Gson();
//            allIllusts = new ArrayList<>();
//            for (int i = 0; i < allItems.size(); i++) {
//                allIllusts.add(gson.fromJson(allItems.get(i).getIllustGson(), IllustsBean.class));
//            }
//            mRefreshLayout.finishRefresh(true);
//            emitter.onComplete();
//        }).subscribeOn(Schedulers.io())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(new Observer<String>() {
//            @Override
//            public void onSubscribe(Disposable d) {
//
//            }
//
//            @Override
//            public void onNext(String s) {
//            }
//
//            @Override
//            public void onError(Throwable e) {
//                e.printStackTrace();
//                Common.showToast(e.toString());
//                mRefreshLayout.finishRefresh(false);
//            }
//
//            @Override
//            public void onComplete() {
//                mOnPrepared.showData(allItems);
//            }
//        });
//    }
//
//
//    private void getNextData() {
//        Observable.create((ObservableOnSubscribe<String>) emitter -> {
//            emitter.onNext("开始查询数据库");
//            List<DownloadEntity> temp = AppDatabase.getAppDatabase(mContext).downloadDao().getAll();
//            final int lastSize = nowIndex;
//            nowIndex += temp.size();
//            allItems.addAll(temp);
//            emitter.onNext("开始转换数据类型");
//            Thread.sleep(500);
//            Gson gson = new Gson();
//            for (int i = lastSize; i < allItems.size(); i++) {
//                allIllusts.add(gson.fromJson(allItems.get(i).getIllustGson(), IllustsBean.class));
//            }
//            mAdapter.notifyItemRangeChanged(lastSize, nowIndex);
//            emitter.onComplete();
//        }).subscribeOn(Schedulers.io())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(new Observer<String>() {
//                    @Override
//                    public void onSubscribe(Disposable d) {
//
//                    }
//
//                    @Override
//                    public void onNext(String s) {
//                    }
//
//                    @Override
//                    public void onError(Throwable e) {
//                        e.printStackTrace();
//                        Common.showToast(e.toString());
//                        mRefreshLayout.finishLoadMore(false);
//                    }
//
//                    @Override
//                    public void onComplete() {
//                        mRefreshLayout.finishLoadMore(true);
//                    }
//                });
//    }
//
//    @Override
//    void initData() {
//        getFirstData();
//    }
//
//    @Subscribe(threadMode = ThreadMode.MAIN)
//    public void onMessageEvent(Channel event) {
//        if(className.contains(event.getReceiver())) {
//
//            DownloadEntity entity = (DownloadEntity) event.getObject();
//            allItems.add(0, entity);
//            allIllusts.add(new Gson().fromJson(entity.getIllustGson(), IllustsBean.class));
//            mAdapter.notifyItemInserted(0);
//            mRecyclerView.scrollToPosition(0);
//            mAdapter.notifyItemRangeChanged(0, allItems.size());
//        }
//    }
//
//    @Override
//    public void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        EventBus.getDefault().register(this);
//        Common.showLog(className + "EVENTBUS 注册了");
//    }
//
//    @Override
//    public void onDestroy() {
//        EventBus.getDefault().unregister(this);
//        super.onDestroy();
//        Common.showLog(className + "EVENTBUS 取消注册了");
//    }
//}
