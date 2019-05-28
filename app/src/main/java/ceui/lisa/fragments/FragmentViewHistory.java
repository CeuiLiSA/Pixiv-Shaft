package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.ProgressBar;

import com.google.gson.Gson;
import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.SpringRecyclerView;
import ceui.lisa.adapters.ViewHistoryAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.LinearItemDecoration;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FragmentViewHistory extends BaseFragment {

    protected ViewHistoryAdapter mAdapter;
    protected SpringRecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected List<IllustHistoryEntity> allItems = new ArrayList<>();
    protected List<IllustsBean> allIllusts = new ArrayList<>();
    protected ProgressBar mProgressBar;
    protected Toolbar mToolbar;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    View initView(View v) {
        mToolbar = v.findViewById(R.id.toolbar);
        mToolbar.setNavigationOnClickListener(view -> getActivity().finish());
        mToolbar.setTitle("浏览记录");
        mProgressBar = v.findViewById(R.id.progress);
        mRecyclerView = v.findViewById(R.id.recyclerView);
        LinearLayoutManager manager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(8.0f)));

//        LayoutAnimationController controller = new LayoutAnimationController(getAnimationSetFromLeft());
//        controller.setDelay(0.1f);
//        controller.setOrder(LayoutAnimationController.ORDER_NORMAL);
//
//        mRecyclerView.setLayoutAnimation(controller);




        //mRecyclerView.setLayoutAnimation(new LayoutAnimationController());
        mRefreshLayout = v.findViewById(R.id.refreshLayout);
        mRefreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
        mRefreshLayout.setOnRefreshListener(layout -> getFirstData());
        mRefreshLayout.setOnLoadMoreListener(layout -> getNextData());
        mRefreshLayout.setEnableLoadMore(false);
        return v;
    }

    private void getNextData() {

    }

    private void getFirstData() {
        mProgressBar.setVisibility(View.VISIBLE);
        allItems.clear();
        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            emitter.onNext("开始查询数据库");
            List<IllustHistoryEntity> temp = AppDatabase.getAppDatabase(mContext).trackDao().getAll();
            allItems.addAll(temp);
            emitter.onNext("开始转换数据类型");
            Thread.sleep(500);
            Gson gson = new Gson();
            allIllusts = new ArrayList<>();
            for (int i = 0; i < allItems.size(); i++) {
                allIllusts.add(gson.fromJson(allItems.get(i).getIllustJson(), IllustsBean.class));
            }
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
                mRefreshLayout.finishRefresh(false);
            }

            @Override
            public void onComplete() {
                mProgressBar.setVisibility(View.INVISIBLE);
                mRecyclerView.setAdapter(mAdapter);
                mRefreshLayout.finishRefresh(true);
            }
        });
    }

    @Override
    void initData() {
        getFirstData();
    }

    @Override
    public void onResume() {
        super.onResume();

    }


//    public static AnimationSet getAnimationSetFromLeft() {
//        AnimationSet animationSet = new AnimationSet(true);
//        TranslateAnimation translateX1 = new TranslateAnimation(RELATIVE_TO_SELF, 1.0f, RELATIVE_TO_SELF, -0.1f,
//                RELATIVE_TO_SELF, 0, RELATIVE_TO_SELF, 0);
//        translateX1.setDuration(300);
//        translateX1.setInterpolator(new DecelerateInterpolator());
//        translateX1.setStartOffset(0);
//
//        TranslateAnimation translateX2 = new TranslateAnimation(RELATIVE_TO_SELF, -0.1f, RELATIVE_TO_SELF, 0.1f,
//                RELATIVE_TO_SELF, 0, RELATIVE_TO_SELF, 0);
//        translateX2.setStartOffset(300);
//        translateX2.setInterpolator(new DecelerateInterpolator());
//        translateX2.setDuration(50);
//
//        TranslateAnimation translateX3 = new TranslateAnimation(RELATIVE_TO_SELF, 0.1f, RELATIVE_TO_SELF, -0f,
//                RELATIVE_TO_SELF, 0, RELATIVE_TO_SELF, 0);
//        translateX3.setStartOffset(350);
//        translateX3.setInterpolator(new DecelerateInterpolator());
//        translateX3.setDuration(50);
//
//        animationSet.addAnimation(translateX1);
//        animationSet.addAnimation(translateX2);
//        animationSet.addAnimation(translateX3);
//        animationSet.setDuration(600);
//
//        return animationSet;
//    }
}
