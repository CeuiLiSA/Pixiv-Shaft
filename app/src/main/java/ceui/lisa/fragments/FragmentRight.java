package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.FragmentTransaction;

import com.scwang.smart.refresh.header.FalsifyFooter;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.TimelineAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.RxRun;
import ceui.lisa.core.RxRunnable;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentNewRightBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.RightRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.QMUIMenuPopup;
import ceui.lisa.view.OnCheckChangeListener;
import ceui.lisa.viewmodel.BaseModel;
import ceui.lisa.viewmodel.DynamicIllustModel;
import androidx.recyclerview.widget.LinearLayoutManager;

public class FragmentRight extends NetListFragment<FragmentNewRightBinding, ListIllust, IllustsBean> {

    private FragmentRecmdUserHorizontal headerFragment;
    private boolean isTimelineMode = !Shaft.sSettings.isUseStaggeredLayout();
    // 动态页类型：true=插画/漫画(默认)，false=小说。fixes #844
    private boolean isIllustMode = true;
    private FragmentNewNovels novelFragment;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new_right;
    }

    @Override
    public Class<? extends BaseModel> modelClass() {
        return DynamicIllustModel.class;
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return isTimelineMode ? new TimelineAdapter(allItems, mContext) : new IAdapter(allItems, mContext);
    }

    @Override
    public void initView() {
        super.initView();

        if (Dev.hideMainActivityStatus) {
            ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
            headParams.height = Shaft.statusHeight;
            baseBind.head.setLayoutParams(headParams);
        }

        baseBind.toolbar.inflateMenu(R.menu.fragment_left);
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActivity instanceof MainActivity) {
                    ((MainActivity) mActivity).getDrawer().openDrawer(GravityCompat.START, true);
                }
            }
        });
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_search) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索");
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
        baseBind.seeMore.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐用户");
            String handoffKey = null;
            if (headerFragment != null && headerFragment.allItems != null && !headerFragment.allItems.isEmpty()) {
                // Hand off via in-memory map rather than Intent extras: the
                // full UserPreviewsBean graph easily exceeds the ~1MB binder
                // transaction limit and crashes on Android 15 (#820). We
                // still want the detail page to show the same batch the
                // user was just looking at, so stash a snapshot under a
                // unique key and pass only the key.
                handoffKey = UUID.randomUUID().toString();
                RecmdUserMap.store.put(handoffKey, new RecmdUserSnapshot(
                        new ArrayList<>(headerFragment.allItems),
                        headerFragment.mRemoteRepo.getNextUrl()
                ));
                intent.putExtra(Params.USER_MODEL, handoffKey);
            }
            // 即便我们的 intent 自身只装了 2 个 String，部分机型/系统版本会在
            // startActivity 的 binder 事务里附带调用方 Activity 的状态快照，
            // 仍可能触发 TransactionTooLargeException。兜底捕获，避免崩溃。
            try {
                startActivity(intent);
            } catch (RuntimeException e) {
                Common.showLog("FragmentRight seeMore startActivity failed: " + e.getMessage());
                if (handoffKey != null) {
                    RecmdUserMap.store.remove(handoffKey);
                }
                Common.showToast("打开页面失败，请稍后重试");
            }
        });
        baseBind.glareLayout.setListener(new OnCheckChangeListener() {
            final String[] types = {Params.TYPE_ALL, Params.TYPE_PUBLIC, Params.TYPE_PRIVATE};
            @Override
            public void onSelect(int index, View view) {
                Common.showLog("glareLayout onSelect " + index);
                if (index < types.length) {
                    restrict = types[index];
                }
                if (isIllustMode) {
                    ((RightRepo) mRemoteRepo).setRestrict(restrict);
                    forceRefresh();
                } else if (novelFragment != null) {
                    novelFragment.setRestrict(restrict);
                }
            }

            @Override
            public void onReselect(int index, View view) {
                Common.showLog("glareLayout onReselect " + index);
                if (isIllustMode) {
                    forceRefresh();
                } else if (novelFragment != null) {
                    novelFragment.forceRefresh();
                }
            }
        });
        baseBind.dynamicTypeSwitcher.setOnClickListener(v -> showTypeSwitcherMenu(v));
        baseBind.dynamicTitleLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollToTop();
            }
        });
        baseBind.timelineToggle.setOnClickListener(v -> toggleTimelineMode());
        if (isTimelineMode) {
            int primaryColor = Common.resolveThemeAttribute(mContext, androidx.appcompat.R.attr.colorPrimary);
            baseBind.timelineToggle.setColorFilter(primaryColor);
        }
    }

    private void showTypeSwitcherMenu(View anchor) {
        CharSequence illustTitle = mContext.getString(R.string.dynamic_type_illust_manga);
        CharSequence novelTitle = mContext.getString(R.string.string_171);
        CharSequence[] titles = new CharSequence[]{illustTitle, novelTitle};
        QMUIMenuPopup.show(mContext, anchor, titles, (index, text) -> {
            boolean wantIllust = index == 0;
            switchMode(wantIllust);
            baseBind.dynamicTypeSwitcher.setText(
                    wantIllust ? R.string.dynamic_type_illust_manga : R.string.string_171);
        });
    }

    private void switchMode(boolean wantIllust) {
        if (isIllustMode == wantIllust) return;
        isIllustMode = wantIllust;
        if (wantIllust) {
            baseBind.refreshLayout.setVisibility(View.VISIBLE);
            baseBind.novelListContainer.setVisibility(View.GONE);
            baseBind.timelineToggle.setVisibility(View.VISIBLE);
            if (novelFragment != null && novelFragment.isAdded()) {
                getChildFragmentManager().beginTransaction()
                        .hide(novelFragment)
                        .commitNowAllowingStateLoss();
            }
            // 切回插画时同步当前 restrict 并刷新（若与上次一致则跳过）
            if (mRemoteRepo instanceof RightRepo) {
                RightRepo repo = (RightRepo) mRemoteRepo;
                if (!java.util.Objects.equals(repo.getRestrict(), restrict)) {
                    repo.setRestrict(restrict);
                    forceRefresh();
                }
            }
        } else {
            baseBind.refreshLayout.setVisibility(View.GONE);
            baseBind.novelListContainer.setVisibility(View.VISIBLE);
            baseBind.timelineToggle.setVisibility(View.GONE);
            if (novelFragment == null) {
                novelFragment = FragmentNewNovels.newInstance(restrict);
                getChildFragmentManager().beginTransaction()
                        .add(R.id.novel_list_container, novelFragment, "FragmentNewNovels")
                        .commitNowAllowingStateLoss();
            } else {
                getChildFragmentManager().beginTransaction()
                        .show(novelFragment)
                        .commitNowAllowingStateLoss();
                novelFragment.setRestrict(restrict);
            }
        }
    }

    private void toggleTimelineMode() {
        isTimelineMode = !isTimelineMode;
        Shaft.sSettings.setUseStaggeredLayout(!isTimelineMode);
        Local.setSettings(Shaft.sSettings);
        int primaryColor = Common.resolveThemeAttribute(mContext, androidx.appcompat.R.attr.colorPrimary);
        int unselectedColor = mContext.getResources().getColor(R.color.glare_unselected_text);
        baseBind.timelineToggle.setColorFilter(isTimelineMode ? primaryColor : unselectedColor);

        mRecyclerView.setItemAnimator(null);
        while (mRecyclerView.getItemDecorationCount() > 0) {
            mRecyclerView.removeItemDecorationAt(0);
        }

        if (isTimelineMode) {
            mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
            mAdapter = new TimelineAdapter(allItems, mContext);
        } else {
            staggerRecyclerView();
            mAdapter = new IAdapter(allItems, mContext);
        }
        mRecyclerView.setAdapter(mAdapter);
        onAdapterPrepared();
    }

    @Override
    public BaseRepo repository() {
        return new RightRepo(restrict);
    }

    @Override
    public void initRecyclerView() {
        if (isTimelineMode) {
            mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        } else {
            staggerRecyclerView();
        }
    }

    @Override
    public void lazyData() {
        super.lazyData();

        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        FragmentRecmdUserHorizontal recmdUser = new FragmentRecmdUserHorizontal();
        headerFragment = recmdUser;
        transaction.add(R.id.user_recmd_fragment, recmdUser, "FragmentRecmdUserHorizontal");
        transaction.commitNowAllowingStateLoss();

        baseBind.refreshLayout.autoRefresh();
    }

    @Override
    public boolean autoRefresh() {
        return false;
    }

    private String restrict = Params.TYPE_ALL;

    @Override
    public void showDataBase() {
        RxRun.runOn(new RxRunnable<List<IllustsBean>>() {
            @Override
            public List<IllustsBean> execute() throws Exception {
                Thread.sleep(100);
                List<IllustRecmdEntity> entities = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
                List<IllustsBean> temp = new ArrayList<>();
                for (int i = 0; i < entities.size(); i++) {
                    IllustsBean illustsBean = Shaft.sGson.fromJson(
                            entities.get(i).getIllustJson(), IllustsBean.class);
                    temp.add(illustsBean);
                }
                return temp;
            }
        }, new NullCtrl<List<IllustsBean>>() {
            @Override
            public void success(List<IllustsBean> illustsBeans) {
                allItems.addAll(illustsBeans);
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
    public void forceRefresh() {
        emptyRela.setVisibility(View.INVISIBLE);
        super.forceRefresh();
    }
}
