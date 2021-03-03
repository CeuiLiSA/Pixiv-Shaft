package ceui.lisa.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapterWithHeadView;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.helper.IllustFilter;
import ceui.lisa.helper.StaggeredtManager;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.model.RecmdIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserModel;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.CallBackReceiver;
import ceui.lisa.repo.RecmdIllustRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.view.SpacesItemWithHeadDecoration;
import ceui.lisa.viewmodel.BaseModel;
import ceui.lisa.viewmodel.RecmdModel;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Call;

public class FragmentRecmdIllust extends NetListFragment<FragmentBaseListBinding,
        RecmdIllust, IllustsBean> {

    private String dataType;
    private List<IllustRecmdEntity> localData;
    private BroadcastReceiver relatedReceiver;

    public static FragmentRecmdIllust newInstance(String dataType) {
        Bundle args = new Bundle();
        args.putString(Params.DATA_TYPE, dataType);
        FragmentRecmdIllust fragment = new FragmentRecmdIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        dataType = bundle.getString(Params.DATA_TYPE);
    }

    @Override
    public Class<? extends BaseModel<IllustsBean>> modelClass() {
        return RecmdModel.class;
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        if (Dev.isDev) {
            localData = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
            return new RecmdIllustRepo(dataType) {
                @Override
                public boolean localData() {
                    return !Common.isEmpty(localData);
                }
            };
        } else {
            return new RecmdIllustRepo(dataType);
        }
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapterWithHeadView(allItems, mContext, dataType).setShowRelated(true);
    }

    @Override
    public void onAdapterPrepared() {
        super.onAdapterPrepared();
        IntentFilter intentFilter = new IntentFilter();
        relatedReceiver = new CallBackReceiver(new BaseReceiver.CallBack() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    int index = bundle.getInt(Params.INDEX);
                    ListIllust listIllust = (ListIllust) bundle.getSerializable(Params.CONTENT);
                    if (listIllust != null){
                        if (!Common.isEmpty(listIllust.getList())) {
                            if (!isAdded()) {
                                return;
                            }
                            List<IllustsBean> temp = new ArrayList<>();
                            for (int i = 0; i < listIllust.getList().size(); i++) {
                                listIllust.getList().get(i).setRelated(true);
                                if (i < 5) {
                                    temp.add(listIllust.getList().get(i));
                                } else {
                                    break;
                                }
                            }

                            if (!Common.isEmpty(temp)) {
                                mModel.load(temp, index);
                                Common.showToast(index);
                                mAdapter.notifyItemRangeInserted(index + 1, temp.size());
                                mAdapter.notifyItemRangeChanged(index + 1, allItems.size() - index - 1);
                            }
                        }
                    }
                }
            }
        });
        intentFilter.addAction(Params.FRAGMENT_ADD_RELATED_DATA);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(relatedReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (relatedReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(relatedReceiver);
        }
    }

    @Override
    public void initRecyclerView() {
        StaggeredtManager layoutManager =
                new StaggeredtManager(Shaft.sSettings.getLineCount(), StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_NONE);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemWithHeadDecoration(DensityUtil.dp2px(8.0f)));
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_239) + dataType;
    }

    @Override
    public boolean showToolbar() {
        return getString(R.string.string_240).equals(dataType);
    }

    @Override
    public void onFirstLoaded(List<IllustsBean> illustsBeans) {
        ((RecmdModel) mModel).getRankList().clear();
        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            emitter.onNext("开始写入数据库");
            if (allItems != null) {
                if (allItems.size() >= 20) {
                    for (int i = 0; i < 20; i++) {
                        insertViewHistory(allItems.get(i));
                    }
                } else {
                    for (int i = 0; i < allItems.size(); i++) {
                        insertViewHistory(allItems.get(i));
                    }
                }
            }
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<String>() {
                    @Override
                    public void success(String s) {

                    }
                });
        ((RecmdModel) mModel).getRankList().addAll(((RecmdIllust) mResponse).getRanking_illusts());
        ((IAdapterWithHeadView) mAdapter).setHeadData(((RecmdModel) mModel).getRankList());
    }

    private void insertViewHistory(IllustsBean illustsBean) {
        IllustRecmdEntity illustRecmdEntity = new IllustRecmdEntity();
        illustRecmdEntity.setIllustID(illustsBean.getId());
        illustRecmdEntity.setIllustJson(Shaft.sGson.toJson(illustsBean));
        illustRecmdEntity.setTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).recmdDao().insert(illustRecmdEntity);
    }

    @Override
    public void showDataBase() {
        if (Common.isEmpty(localData)) {
            return;
        }
        Observable.create((ObservableOnSubscribe<List<IllustRecmdEntity>>) emitter -> {
            Thread.sleep(100);
            emitter.onNext(localData);
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(entities -> {
                    Common.showLog(className + entities.size());
                    List<IllustsBean> temp = new ArrayList<>();
                    for (int i = 0; i < entities.size(); i++) {
                        IllustsBean illustsBean = Shaft.sGson.fromJson(
                                entities.get(i).getIllustJson(), IllustsBean.class);
                        if (!IllustFilter.judge(illustsBean)) {
                            temp.add(illustsBean);
                        }
                    }
                    return temp;
                })
                .subscribe(new NullCtrl<List<IllustsBean>>() {
                    @Override
                    public void success(List<IllustsBean> illustsBeans) {
                        allItems.addAll(illustsBeans);
                        ((RecmdModel) mModel).getRankList().addAll(illustsBeans);
                        ((IAdapterWithHeadView) mAdapter).setHeadData(((RecmdModel) mModel).getRankList());
                        mAdapter.notifyItemRangeInserted(mAdapter.headerSize(), allItems.size());
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.refreshLayout.finishRefresh(isSuccess);
                        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
                });
    }
}
