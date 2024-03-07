package ceui.lisa.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.scwang.smart.refresh.header.FalsifyFooter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapterWithHeadView;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.core.RxRun;
import ceui.lisa.core.RxRunnable;
import ceui.lisa.core.TryCatchObserverImpl;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.helper.IllustNovelFilter;
import ceui.lisa.helper.StaggeredManager;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.model.ListIllust;
import ceui.lisa.model.RecmdIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.CallBackReceiver;
import ceui.lisa.repo.RecmdIllustRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.view.SpacesItemWithHeadDecoration;
import ceui.lisa.viewmodel.BaseModel;
import ceui.lisa.viewmodel.RecmdModel;
import ceui.loxia.ObjectPool;

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
        return new IAdapterWithHeadView(allItems, mContext, dataType).setShowRelated(Shaft.sSettings.isShowRelatedWhenStar());
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
                                if (index < allItems.size()) {
                                    mModel.load(temp, index);
//                                    Common.showToast(index);
                                    mAdapter.notifyItemRangeInserted(index + 1, temp.size());
                                    mAdapter.notifyItemRangeChanged(index + 1, allItems.size() - index - 1);
                                }
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
        StaggeredManager layoutManager =
                new StaggeredManager(Shaft.sSettings.getLineCount(), StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_NONE);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemWithHeadDecoration(DensityUtil.dp2px(8.0f)));
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.recommend) + dataType;
    }

    @Override
    public boolean showToolbar() {
        return getString(R.string.type_manga).equals(dataType);
    }

    @Override
    public void onFirstLoaded(List<IllustsBean> illustsBeans) {
        ((RecmdModel) mModel).getRankList().clear();
        RxRun.runOn(new RxRunnable<Void>() {
            @Override
            public Void execute() {
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
                return null;
            }
        }, new TryCatchObserverImpl<>());
        mResponse.getRanking_illusts().forEach(new Consumer<IllustsBean>() {
            @Override
            public void accept(IllustsBean illustsBean) {
                ObjectPool.INSTANCE.updateIllust(illustsBean);
            }
        });
        ((RecmdModel) mModel).getRankList().addAll(mResponse.getRanking_illusts());
        ((IAdapterWithHeadView) mAdapter).setHeadData(((RecmdModel) mModel).getRankList());
        mModel.tidyAppViewModel(((RecmdModel) mModel).getRankList());
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
        RxRun.runOn(new RxRunnable<List<IllustsBean>>() {
            @Override
            public List<IllustsBean> execute() throws Exception {
                Thread.sleep(100);
                List<IllustsBean> temp = new ArrayList<>();
                for (int i = 0; i < localData.size(); i++) {
                    IllustsBean illustsBean = Shaft.sGson.fromJson(
                            localData.get(i).getIllustJson(), IllustsBean.class);
                    if (!IllustNovelFilter.judge(illustsBean)) {
                        temp.add(illustsBean);
                    }
                }
                return temp;
            }
        }, new NullCtrl<List<IllustsBean>>() {
            @Override
            public void success(List<IllustsBean> illustsBeans) {
                allItems.addAll(illustsBeans);
                illustsBeans.forEach(new Consumer<IllustsBean>() {
                    @Override
                    public void accept(IllustsBean illustsBean) {
                        ObjectPool.INSTANCE.updateIllust(illustsBean);
                    }
                });
                ((RecmdModel) mModel).getRankList().addAll(illustsBeans);
                mModel.tidyAppViewModel(illustsBeans);
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
