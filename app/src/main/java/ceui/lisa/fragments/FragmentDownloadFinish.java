package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.google.gson.Gson;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.DownlistAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FragmentDownloadFinish extends BaseAsyncFragment<DownlistAdapter, DownloadEntity> {

    protected int nowIndex = 0;
    private List<IllustsBean> allIllusts = new ArrayList<>();
    private List<String> filePaths = new ArrayList<>();

    @Override
    public void getFirstData() {
        allItems.clear();
        nowIndex = 0;
        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            List<DownloadEntity> temp = AppDatabase.getAppDatabase(mContext).downloadDao().getAll(PAGE_SIZE, nowIndex);
            nowIndex += temp.size();
            allItems.addAll(temp);
            Thread.sleep(500);
            Gson gson = new Gson();
            allIllusts = new ArrayList<>();
            filePaths = new ArrayList<>();
            for (int i = 0; i < allItems.size(); i++) {
                allIllusts.add(gson.fromJson(allItems.get(i).getIllustGson(), IllustsBean.class));
                filePaths.add(allItems.get(i).getFilePath());
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
                        mRefreshLayout.finishRefresh(false);
                    }

                    @Override
                    public void onComplete() {
                        showFirstData();
                    }
                });
    }

    @Override
    public void initAdapter() {
        mAdapter = new DownlistAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 0) {
                    Intent intent = new Intent(mContext, ImageDetailActivity.class);
                    intent.putExtra("illust", (Serializable) filePaths);
                    intent.putExtra("dataType", "下载详情");
                    intent.putExtra("index", position);
                    startActivity(intent);
                } else if (viewType == 1) {
                    Intent intent = new Intent(mContext, UActivity.class);
                    intent.putExtra(Params.USER_ID, allIllusts.get(position).getUser().getId());
                    startActivity(intent);
                }
            }
        });
    }

    @Override
    boolean hasNext() {
        return true;
    }

    @Override
    public void getNextData() {
        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            emitter.onNext("开始查询数据库");
            List<DownloadEntity> temp = AppDatabase.getAppDatabase(mContext).downloadDao().getAll(PAGE_SIZE, nowIndex);
            final int lastSize = nowIndex;
            nowIndex += temp.size();
            allItems.addAll(temp);
            emitter.onNext("开始转换数据类型");
            Thread.sleep(500);
            Gson gson = new Gson();
            for (int i = lastSize; i < allItems.size(); i++) {
                allIllusts.add(gson.fromJson(allItems.get(i).getIllustGson(), IllustsBean.class));
                filePaths.add(allItems.get(i).getFilePath());
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

    @Override
    boolean showToolbar() {
        return false;
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
        if (className.contains(event.getReceiver())) {

            nowIndex++;
            mRecyclerView.setVisibility(View.VISIBLE);
            noData.setVisibility(View.INVISIBLE);
            DownloadEntity entity = (DownloadEntity) event.getObject();
            allItems.add(0, entity);
            allIllusts.add(new Gson().fromJson(entity.getIllustGson(), IllustsBean.class));
            filePaths.add(0, entity.getFilePath());
            mAdapter.notifyItemInserted(0);
            mRecyclerView.scrollToPosition(0);
            mAdapter.notifyItemRangeChanged(0, allItems.size());
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);
        Common.showLog(className + "EVENTBUS 注册了");
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
        Common.showLog(className + "EVENTBUS 取消注册了");
    }
}
