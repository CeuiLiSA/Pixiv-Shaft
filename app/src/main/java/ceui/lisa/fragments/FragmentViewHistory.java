package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.google.gson.Gson;
import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.ViewHistoryAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.ListObserver;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.fragments.BaseListFragment.PAGE_SIZE;

public class FragmentViewHistory extends BaseFragment {

    private ViewHistoryAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private RefreshLayout mRefreshLayout;
    private List<IllustHistoryEntity> allItems = new ArrayList<>();
    private List<IllustsBean> allIllusts = new ArrayList<>();
    private ProgressBar mProgressBar;
    private Toolbar mToolbar;
    private int nowIndex = 0;
    private ImageView noData;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    View initView(View v) {
        mToolbar = v.findViewById(R.id.toolbar);
        ((TemplateFragmentActivity) getActivity()).setSupportActionBar(mToolbar);
        mToolbar.setNavigationOnClickListener(view -> getActivity().finish());
        mToolbar.setTitle("浏览记录");
        mProgressBar = v.findViewById(R.id.progress);
        mRecyclerView = v.findViewById(R.id.recyclerView);
        noData = v.findViewById(R.id.no_data);
        noData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getFirstData();
            }
        });
        LinearLayoutManager manager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(8.0f)));
        mRefreshLayout = v.findViewById(R.id.refreshLayout);
        mRefreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
        mRefreshLayout.setOnRefreshListener(layout -> getFirstData());
        mRefreshLayout.setOnLoadMoreListener(layout -> getNextData());
        mRefreshLayout.setEnableLoadMore(true);
        return v;
    }

    private void getNextData() {
        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            emitter.onNext("开始查询数据库");
            List<IllustHistoryEntity> temp = AppDatabase.getAppDatabase(mContext)
                    .downloadDao().getAllViewHistory(PAGE_SIZE, nowIndex);
            final int lastSize = nowIndex;
            nowIndex += temp.size();
            allItems.addAll(temp);
            emitter.onNext("开始转换数据类型");
            Thread.sleep(500);
            Gson gson = new Gson();
            for (int i = lastSize; i < allItems.size(); i++) {
                allIllusts.add(gson.fromJson(allItems.get(i).getIllustJson(), IllustsBean.class));
            }
            mAdapter.notifyItemRangeChanged(lastSize, nowIndex);
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        Common.showToast(e.toString());
                        mRefreshLayout.finishLoadMore(false);
                    }

                    @Override
                    public void onComplete() {
                        mRefreshLayout.finishLoadMore(true);
                    }
                });
    }

    private void getFirstData() {
        mProgressBar.setVisibility(View.VISIBLE);
        noData.setVisibility(View.INVISIBLE);
        allItems.clear();
        nowIndex = 0;
        Observable.create((ObservableOnSubscribe<List<IllustHistoryEntity>>) emitter -> {
            Common.showLog(className + "11111");
            List<IllustHistoryEntity> temp = AppDatabase.getAppDatabase(mContext)
                    .downloadDao().getAllViewHistory(PAGE_SIZE, nowIndex);
            nowIndex += temp.size();
            allItems.addAll(temp);
            Common.showLog(className + "222222 allItems " + allItems.size());
            emitter.onNext(temp);
        })
                .map(new Function<List<IllustHistoryEntity>, ListIllustResponse>() {
                    @Override
                    public ListIllustResponse apply(List<IllustHistoryEntity> illustHistoryEntities) throws Exception {
                        Thread.sleep(500);
                        Gson gson = new Gson();

                        allIllusts = new ArrayList<>();
                        for (int i = 0; i < allItems.size(); i++) {
                            allIllusts.add(gson.fromJson(allItems.get(i).getIllustJson(), IllustsBean.class));
                        }

                        ListIllustResponse response = new ListIllustResponse();
                        response.setIllusts(allIllusts);
                        return response;
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ListObserver<ListIllustResponse>() {
                    @Override
                    public void success(ListIllustResponse listIllustResponse) {
                        mAdapter = new ViewHistoryAdapter(allItems, mContext);
                        mAdapter.setOnItemClickListener(new OnItemClickListener() {
                            @Override
                            public void onItemClick(View v, int position, int viewType) {
                                IllustChannel.get().setIllustList(allIllusts);
                                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                                intent.putExtra("position", position);
                                startActivity(intent);
                            }
                        });
                        mProgressBar.setVisibility(View.INVISIBLE);
                        noData.setVisibility(View.GONE);
                        mRecyclerView.setVisibility(View.VISIBLE);
                        mRecyclerView.setAdapter(mAdapter);
                        mRefreshLayout.finishRefresh(true);
                    }

                    @Override
                    public void dataError() {
                        mRefreshLayout.finishRefresh(false);
                        mProgressBar.setVisibility(View.INVISIBLE);
                        mRecyclerView.setVisibility(View.INVISIBLE);
                        noData.setVisibility(View.VISIBLE);
                        noData.setImageResource(R.mipmap.no_data);
                    }

                    @Override
                    public void netError() {
                        mRecyclerView.setVisibility(View.INVISIBLE);
                        noData.setVisibility(View.VISIBLE);
                        noData.setImageResource(R.mipmap.load_error);
                    }
                });
    }

    @Override
    void initData() {
        getFirstData();
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.delete_all, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
            if (allItems.size() == 0) {
                Common.showToast("没有浏览历史");
            } else {

                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle("Shaft提示");
                builder.setMessage("这将会删除所有的本地浏览历史");
                builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllHistory();
                        Common.showToast("删除成功");
                        getFirstData();
                    }
                });
                builder.setNegativeButton("取消", null);
                AlertDialog alertDialog = builder.create();
                alertDialog.show();

            }
        }
        return super.onOptionsItemSelected(item);
    }
}
