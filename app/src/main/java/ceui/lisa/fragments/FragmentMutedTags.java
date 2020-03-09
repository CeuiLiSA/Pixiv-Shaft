package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.ClassicsHeader;
import com.scwang.smartrefresh.layout.listener.OnRefreshLoadMoreListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BAdapter;
import ceui.lisa.core.BindFragment;
import ceui.lisa.core.ModelFragment;
import ceui.lisa.core.TagFilter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.TagMuteEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.LinearItemDecoration;
import ceui.lisa.viewmodel.MutedTagsModel;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

public class FragmentMutedTags extends BindFragment<FragmentBaseListBinding> {

    private BAdapter mAdapter;
    private List<TagsBean> result = new ArrayList<>();

    @Override
    public void getLayout() {
        mLayoutID = R.layout.fragment_base_list;
    }

    @Override
    public void initData() {
        bind.refreshLayout.autoRefresh();
    }

    @Override
    public void initView(View view) {
        bind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        bind.refreshLayout.setRefreshHeader(new ClassicsHeader(mContext));
        bind.refreshLayout.setOnRefreshLoadMoreListener(new OnRefreshLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {

            }

            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                getFirstData();
            }
        });
        bind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        bind.recyclerView.setHasFixedSize(true);
        bind.recyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(16.0f)));
        BaseItemAnimator baseItemAnimator = new LandingAnimator();
        baseItemAnimator.setAddDuration(NetListFragment.animateDuration);
        baseItemAnimator.setRemoveDuration(NetListFragment.animateDuration);
        baseItemAnimator.setMoveDuration(NetListFragment.animateDuration);
        baseItemAnimator.setChangeDuration(NetListFragment.animateDuration);
        bind.recyclerView.setItemAnimator(baseItemAnimator);
        bind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
        bind.toolbar.inflateMenu(R.menu.delete_all);
        bind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_delete) {
                    if (result.size() == 0) {
                        Common.showToast("当前没有可删除的屏蔽标签");
                    } else {
                        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                        builder.setTitle("PixShaft 提示");
                        builder.setMessage("这将会删除所有的本地屏蔽标签");
                        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                AppDatabase.getAppDatabase(mContext).searchDao().deleteAllMutedTags();
                                Common.showToast("删除成功");
                                mAdapter.clear();
                            }
                        });
                        builder.setNegativeButton("取消", null);
                        AlertDialog alertDialog = builder.create();
                        alertDialog.show();
                    }
                }
                return true;
            }
        });
        bind.noData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bind.noData.setVisibility(View.INVISIBLE);
                bind.refreshLayout.autoRefresh();
            }
        });
        bind.toolbar.setTitle(getString(R.string.muted_history));
    }

    public void getFirstData() {
        bind.refreshLayout.finishRefresh(true);
        mAdapter = new BAdapter(result, mContext, true);
        bind.recyclerView.setAdapter(mAdapter);
        result.addAll(TagFilter.getMutedTags());
        if (result.size() != 0) {
            mAdapter.notifyItemRangeInserted(0, result.size());
            bind.recyclerView.setVisibility(View.VISIBLE);
            bind.noData.setVisibility(View.INVISIBLE);
        } else {
            bind.recyclerView.setVisibility(View.INVISIBLE);
            bind.noData.setVisibility(View.VISIBLE);
        }
    }
}
