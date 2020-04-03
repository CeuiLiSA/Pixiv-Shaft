package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.TestAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.databinding.FragmentTestBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.viewmodel.BaseModel;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.FadeInLeftAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

import static ceui.lisa.activities.Shaft.sUserModel;
import static ceui.lisa.fragments.ListFragment.animateDuration;

public class FragmentTest extends BaseFragment<FragmentTestBinding> {

    private BaseModel<UserPreviewsBean> mModel;
    private UAdapter mAdapter;

    public static FragmentTest newInstance() {
        return new FragmentTest();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_test;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mModel = (BaseModel<UserPreviewsBean>) new ViewModelProvider(this).get(BaseModel.class);
        mModel.getContent().observe(getViewLifecycleOwner(), new Observer<List<UserPreviewsBean>>() {
            @Override
            public void onChanged(List<UserPreviewsBean> list) {
                mAdapter.notifyItemRangeInserted(mModel.getLastSize(), list.size());
            }
        });
        mAdapter = new UAdapter(mModel.getContent().getValue(), mContext);
        baseBind.recyclerView.setAdapter(mAdapter);
        if (!mModel.isLoaded()) {
            baseBind.refreshLayout.autoRefresh();
        }
    }

    @Override
    public void initView(View view) {
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.setHasFixedSize(true);
//        BaseItemAnimator baseItemAnimator = new FadeInLeftAnimator();
//        baseItemAnimator.setAddDuration(animateDuration);
//        baseItemAnimator.setRemoveDuration(animateDuration);
//        baseItemAnimator.setMoveDuration(animateDuration);
//        baseItemAnimator.setChangeDuration(animateDuration);
//        baseBind.recyclerView.setItemAnimator(baseItemAnimator);
        baseBind.refreshLayout.setEnableRefresh(true);
        baseBind.refreshLayout.setRefreshHeader(new MaterialHeader(mContext));
        baseBind.refreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                Common.showLog("执行了一次 refreshLayout 222");
                mAdapter.clear();
                Retro.getAppApi()
                        .getRecmdUser(sUserModel.getResponse().getAccess_token())
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new NullCtrl<ListUser>() {
                            @Override
                            public void success(ListUser listUser) {
                                mModel.load(listUser.getList(), getClass());
                                isloaded = true;
                                baseBind.refreshLayout.finishRefresh();
                            }
                        });
            }
        });


    }
//
//    @Override
//    public void setUserVisibleHint(boolean isVisibleToUser) {
//        super.setUserVisibleHint(isVisibleToUser);
//        if(isloaded)
//    }
}
