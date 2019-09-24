package ceui.lisa.fragments;

import android.os.Handler;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.KissAdapter;
import ceui.lisa.databinding.FragmentABinding;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.FadeInAnimator;
import jp.wasabeef.recyclerview.animators.FadeInDownAnimator;
import jp.wasabeef.recyclerview.animators.FadeInLeftAnimator;
import jp.wasabeef.recyclerview.animators.FlipInBottomXAnimator;
import jp.wasabeef.recyclerview.animators.FlipInLeftYAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;
import jp.wasabeef.recyclerview.animators.ScaleInAnimator;
import jp.wasabeef.recyclerview.animators.ScaleInBottomAnimator;
import jp.wasabeef.recyclerview.animators.SlideInDownAnimator;

import static ceui.lisa.fragments.FragmentList.animateDuration;

public class FragmentA extends BaseBindFragment<FragmentABinding> {

    private KissAdapter mAdapter;
    private List<String> allItems = new ArrayList<>();
    private Handler handler;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_a;
    }

    @Override
    void initData() {
        handler = new Handler();
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
        baseBind.toolbar.inflateMenu(R.menu.fragment_a);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                BaseItemAnimator baseItemAnimator = null;
                if (item.getItemId() == R.id.action_1) {
                    baseItemAnimator = new FadeInAnimator();
                } else if (item.getItemId() == R.id.action_2) {
                    baseItemAnimator = new FadeInDownAnimator();
                } else if (item.getItemId() == R.id.action_3) {
                    baseItemAnimator = new FadeInLeftAnimator();
                } else if (item.getItemId() == R.id.action_4) {
                    baseItemAnimator = new FlipInBottomXAnimator();
                } else if (item.getItemId() == R.id.action_5) {
                    baseItemAnimator = new FlipInLeftYAnimator();
                } else if (item.getItemId() == R.id.action_6) {
                    baseItemAnimator = new LandingAnimator();
                } else if (item.getItemId() == R.id.action_7) {
                    baseItemAnimator = new ScaleInAnimator();
                } else if (item.getItemId() == R.id.action_8) {
                    baseItemAnimator = new ScaleInBottomAnimator();
                } else if (item.getItemId() == R.id.action_9) {
                    baseItemAnimator = new SlideInDownAnimator();
                }
                baseItemAnimator.setAddDuration(animateDuration);
                baseItemAnimator.setRemoveDuration(animateDuration);
                baseItemAnimator.setMoveDuration(animateDuration);
                baseItemAnimator.setChangeDuration(animateDuration);
                baseBind.recyclerView.setItemAnimator(baseItemAnimator);
                baseBind.refreshLayout.autoRefresh();

                return false;
            }
        });
        mAdapter = new KissAdapter(allItems, mContext);
        baseBind.recyclerView.setAdapter(mAdapter);
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
        baseBind.recyclerView.setItemAnimator(new FadeInAnimator());
        baseBind.refreshLayout.setPrimaryColorsId(R.color.white);
        baseBind.refreshLayout.setEnableLoadMore(false);
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        baseBind.refreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
        baseBind.refreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                mAdapter.clear();
                getFirstData();
            }
        });
        baseBind.refreshLayout.autoRefresh();
    }

    @Override
    public void getFirstData() {
        handler.postDelayed(() -> {
            for (int i = 0; i < 20; i++) {
                allItems.add("这是第" + i + "条数据");
            }
            mAdapter.notifyItemRangeInserted(0, allItems.size());
            baseBind.refreshLayout.finishRefresh(true);
        }, 1000L);
    }
}
