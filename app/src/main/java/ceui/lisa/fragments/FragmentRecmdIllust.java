package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapterWithHeadView;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.RecmdIllustRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.view.SpacesItemWithHeadDecoration;
import ceui.lisa.viewmodel.BaseModel;
import ceui.lisa.viewmodel.RecmdModel;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentRecmdIllust extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    private String dataType;
    private List<IllustRecmdEntity> localData;

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
        return new IAdapterWithHeadView(allItems, mContext, dataType);
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
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
        ((RecmdModel) mModel).getRankList().addAll(mResponse.getRanking_illusts());
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
                        if (!TagFilter.judge(illustsBean)) {
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
