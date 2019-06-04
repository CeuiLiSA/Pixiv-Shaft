package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.view.View;

import com.google.gson.Gson;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.SpacesItemDecoration;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * fragment recommend 推荐插画
 */
public class FragmentRecmdIllust extends BaseListFragment<ListIllustResponse,
        IllustStagAdapter, IllustsBean> {

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    void initRecyclerView() {
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(4.0f)));
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
    }


    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRecmdIllust(mUserModel.getResponse().getAccess_token(), true);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust("Bearer " + mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
        mAdapter = new IllustStagAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener((v, position, viewType) -> {
            IllustChannel.get().setIllustList(allItems);
            Intent intent = new Intent(mContext, ViewPagerActivity.class);
            intent.putExtra("position", position);
            startActivity(intent);
        });



        //向FragmentCenter发送数据
        Channel<List<IllustsBean>> channel = new Channel<>();
        channel.setObject(mResponse.getRanking_illusts());
        EventBus.getDefault().post(channel);
        Common.showLog(className + "EVENTBUS 发送了消息");


        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            emitter.onNext("开始写入数据库");
            for (int i = 0; i < allItems.size(); i++) {
                insertViewHistory(allItems.get(i));
            }
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
                    }

                    @Override
                    public void onComplete() {
                    }
                });
    }


    private void insertViewHistory(IllustsBean illustsBean) {
        IllustRecmdEntity illustRecmdEntity = new IllustRecmdEntity();
        illustRecmdEntity.setIllustID(illustsBean.getId());
        Gson gson = new Gson();
        illustRecmdEntity.setIllustJson(gson.toJson(illustsBean));
        illustRecmdEntity.setTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).recmdDao().insert(illustRecmdEntity);
    }

    @Override
    public void showDataBase() {
        Common.showLog(className + "showDataBase");
        Observable.create((ObservableOnSubscribe<List<IllustRecmdEntity>>) emitter -> {
            Common.showLog("开始查询数据库");
            List<IllustRecmdEntity> temp = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
            Thread.sleep(500);
            emitter.onNext(temp);
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(illustRecmdEntities -> {
                    Gson gson = new Gson();
                    List<IllustsBean> temp = new ArrayList<>();
                    for (int i = 0; i < illustRecmdEntities.size(); i++) {
                        temp.add(gson.fromJson(illustRecmdEntities.get(i).getIllustJson(), IllustsBean.class));
                    }
                    return temp;
                })
                .subscribe(new Observer<List<IllustsBean>>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(List<IllustsBean> illustsBeans) {
                        if (illustsBeans != null) {
                            allItems.clear();
                            allItems.addAll(illustsBeans);
                            StaggeredGridLayoutManager layoutManager =
                                    new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
                            mRecyclerView.setLayoutManager(layoutManager);
                            mAdapter = new IllustStagAdapter(allItems, mContext);
                            mAdapter.setOnItemClickListener((v, position, viewType) -> {
                                IllustChannel.get().setIllustList(allItems);
                                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                                intent.putExtra("position", position);
                                startActivity(intent);
                            });
                            mProgressBar.setVisibility(View.INVISIBLE);
                            mRecyclerView.setAdapter(mAdapter);
                            mRefreshLayout.finishRefresh(true);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        Common.showToast(e.toString());
                        mRefreshLayout.finishRefresh(false);
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

}
