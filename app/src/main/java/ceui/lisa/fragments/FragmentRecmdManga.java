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
import ceui.lisa.core.NetControl;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentRecmdFinalBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.view.SpacesItemWithHeadDecoration;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentRecmdManga extends NetListFragment<FragmentRecmdFinalBinding,
        ListIllust, IllustsBean, RecyIllustStaggerBinding> {

    private String dataType;
    private List<IllustsBean> ranking = new ArrayList<>();

    public static FragmentRecmdManga newInstance(String dataType) {
        Bundle args = new Bundle();
        args.putString(Params.DATA_TYPE, dataType);
        FragmentRecmdManga fragment = new FragmentRecmdManga();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        dataType = bundle.getString(Params.DATA_TYPE);
    }

    @Override
    public NetControl<ListIllust> present() {
        return new NetControl<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                if (Dev.isDev) {
                    return null;
                } else {
                    if ("漫画".equals(dataType)) {
                        return Retro.getAppApi().getRecmdManga(Shaft.sUserModel.getResponse().getAccess_token());
                    } else {
                        return Retro.getAppApi().getRecmdIllust(Shaft.sUserModel.getResponse().getAccess_token());
                    }
                }
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapterWithHeadView(allItems, mContext);
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
    public void initLayout() {
        mLayoutID = R.layout.fragment_recmd_final;
    }

    @Override
    public String getToolbarTitle() {
        return "推荐" + dataType;
    }

    @Override
    public boolean showToolbar() {
        return "漫画".equals(dataType);
    }

    @Override
    public void firstSuccess() {
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
        ranking.addAll(mResponse.getRanking_illusts());
        ((IAdapterWithHeadView) mAdapter).setHeadData(ranking);
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
        Observable.create((ObservableOnSubscribe<List<IllustRecmdEntity>>) emitter -> {
            List<IllustRecmdEntity> temp = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
            Thread.sleep(500);
            emitter.onNext(temp);
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(entities -> {
                    List<IllustsBean> temp = new ArrayList<>();
                    for (int i = 0; i < entities.size(); i++) {
                        IllustsBean illustsBean = Shaft.sGson.fromJson(
                                entities.get(i).getIllustJson(), IllustsBean.class);
                        TagFilter.judge(illustsBean);
                        temp.add(illustsBean);
                    }
                    return temp;
                })
                .subscribe(new NullCtrl<List<IllustsBean>>() {
                    @Override
                    public void success(List<IllustsBean> illustsBeans) {
                        allItems.addAll(illustsBeans);
                        ranking.addAll(illustsBeans);
                        ((IAdapterWithHeadView) mAdapter).setHeadData(ranking);
                        mAdapter.notifyItemRangeInserted(mAdapter.headerSize(), allItems.size());
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.refreshLayout.finishRefresh(isSuccess);
                        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
                });
    }

    @Override
    public boolean eventBusEnable() {
        return true;
    }

    @Override
    public void handleEvent(Channel channel) {
        Common.showLog(className + "正在刷新");
        nowRefresh();
    }
}
