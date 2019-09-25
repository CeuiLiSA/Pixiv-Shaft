package ceui.lisa.fragments;

import android.content.Intent;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.gson.Gson;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentR extends FragmentList<ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public Observable<ListIllustResponse> initApi() {
        //return Retro.getAppApi().getRecmdIllust(Shaft.sUserModel.getResponse().getAccess_token(), true);
        return null;
    }

    @Override
    public Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }

    @Override
    public void initAdapter() {
        mAdapter = new IAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener((v, position, viewType) -> {
            IllustChannel.get().setIllustList(allItems);
            Intent intent = new Intent(mContext, ViewPagerActivity.class);
            intent.putExtra("position", position);
            startActivity(intent);
        });
    }

    @Override
    public void firstSuccess() {
        //向FragmentCenter发送数据
        if (mResponse != null) {
            Channel<List<IllustsBean>> channel = new Channel<>();
            channel.setReceiver("FragmentRankHorizontal");
            channel.setObject(mResponse.getRanking_illusts());
            EventBus.getDefault().post(channel);

            Observable.create((ObservableOnSubscribe<String>) emitter -> {
                emitter.onNext("开始写入数据库");
                for (int i = 0; i < allItems.size(); i++) {
                    insertViewHistory(allItems.get(i));
                }
                emitter.onComplete();
            }).subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<String>() {
                        @Override
                        public void success(String s) {

                        }
                    });
        }
    }

    private void insertViewHistory(IllustsBean illustsBean) {
        IllustRecmdEntity illustRecmdEntity = new IllustRecmdEntity();
        illustRecmdEntity.setIllustID(illustsBean.getId());
        Gson gson = new Gson();
        illustRecmdEntity.setIllustJson(gson.toJson(illustsBean));
        illustRecmdEntity.setTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).recmdDao().insert(illustRecmdEntity);
    }

    public void showDataBase() {
        Observable.create((ObservableOnSubscribe<List<IllustRecmdEntity>>) emitter -> {
            List<IllustRecmdEntity> temp = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
            Thread.sleep(500);
            emitter.onNext(temp);
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(entities -> {
                    Gson gson = new Gson();
                    List<IllustsBean> temp = new ArrayList<>();
                    for (int i = 0; i < entities.size(); i++) {
                        temp.add(gson.fromJson(entities.get(i).getIllustJson(), IllustsBean.class));
                    }
                    return temp;
                })
                .subscribe(new NullCtrl<List<IllustsBean>>() {
                    @Override
                    public void success(List<IllustsBean> illustsBeans) {
                        if (illustsBeans.size() >= 20) {
                            allItems.addAll(illustsBeans.subList(0, 20));
                            mAdapter.notifyItemRangeInserted(0, allItems.size());
                        }
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.refreshLayout.finishRefresh(isSuccess);
                        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
                });
    }
}
