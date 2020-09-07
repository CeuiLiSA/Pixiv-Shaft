package ceui.lisa.fragments;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.adapters.StringAdapter;
import ceui.lisa.arch.ListModel;
import ceui.lisa.arch.NewNovelListModel;
import ceui.lisa.base.BaseFragment;
import ceui.lisa.databinding.FragmentUltraBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Common;

public class FragmentUltra extends BaseFragment<FragmentUltraBinding> {

    private NewNovelListModel model;
    private NAdapter mAdapter;

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_ultra;
    }

    @Override
    public void initModel() {
        model = new ViewModelProvider(this).get(NewNovelListModel.class);
    }

    @Override
    protected void initView() {
        mAdapter = new NAdapter(model.getContent().getValue(), mContext);
        Common.showLog(className + "initView " + System.identityHashCode(model.getContent().getValue()));
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.setAdapter(mAdapter);
        model.getContent().observe(this, new Observer<List<NovelBean>>() {
            @Override
            public void onChanged(List<NovelBean> illustsBeans) {
                mAdapter.notifyItemRangeInserted(model.getLastSize(), illustsBeans.size());
                if (model.getState() == 1) {
                    baseBind.refreshLayout.finishRefresh();
                } else if (model.getState() == 2) {
                    baseBind.refreshLayout.finishLoadMore();
                }
            }
        });

        baseBind.refreshLayout.setEnableRefresh(true);
        baseBind.refreshLayout.setEnableLoadMore(true);
        baseBind.refreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                mAdapter.clear();
                model.getFirstData();
            }
        });
        baseBind.refreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                model.loadMore();
            }
        });
    }
}
