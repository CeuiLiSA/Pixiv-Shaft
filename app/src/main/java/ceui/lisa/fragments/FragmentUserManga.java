package ceui.lisa.fragments;


import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.feature.FeatureEntity;
import ceui.lisa.helper.UserIllustJumpHelper;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.UserMangaRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

/**
 * 某人創作的漫画
 */
public class FragmentUserManga extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    private int userID;
    private boolean showToolbar = false;
    private int initialOffset = 0;
    private String targetDate;

    public static FragmentUserManga newInstance(int userID, boolean paramShowToolbar) {
        return newInstance(userID, paramShowToolbar, 0, null);
    }

    public static FragmentUserManga newInstance(int userID, boolean paramShowToolbar, int initialOffset) {
        return newInstance(userID, paramShowToolbar, initialOffset, null);
    }

    public static FragmentUserManga newInstance(int userID, boolean paramShowToolbar, int initialOffset, String targetDate) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        args.putInt(Params.INITIAL_OFFSET, initialOffset);
        if (targetDate != null) args.putString(Params.TARGET_DATE, targetDate);
        FragmentUserManga fragment = new FragmentUserManga();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        showToolbar = bundle.getBoolean(Params.FLAG);
        initialOffset = bundle.getInt(Params.INITIAL_OFFSET, 0);
        targetDate = bundle.getString(Params.TARGET_DATE);
    }

    @Override
    public void onFirstLoaded(List<IllustsBean> response) {
        super.onFirstLoaded(response);
        if (TextUtils.isEmpty(targetDate) || response == null || response.isEmpty()) return;
        int hit = -1;
        for (int i = 0; i < response.size(); i++) {
            String cd = response.get(i).getCreate_date();
            if (cd == null || cd.length() < 10) continue;
            if (cd.substring(0, 10).compareTo(targetDate) <= 0) { hit = i; break; }
        }
        if (hit < 0) hit = response.size() - 1;
        final int adapterPos = hit + mAdapter.headerSize();
        if (mRecyclerView != null) {
            mRecyclerView.postDelayed(() -> {
                scrollToAdapterPos(adapterPos);
                highlightItemAt(adapterPos, 5);
            }, 200L);
        }
        targetDate = null;
    }

    private void scrollToAdapterPos(int pos) {
        RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
        if (lm instanceof StaggeredGridLayoutManager) {
            StaggeredGridLayoutManager sglm = (StaggeredGridLayoutManager) lm;
            sglm.scrollToPositionWithOffset(pos, 0);
            mRecyclerView.post(sglm::invalidateSpanAssignments);
        } else {
            mRecyclerView.scrollToPosition(pos);
        }
    }

    private void highlightItemAt(int adapterPos, int triesLeft) {
        if (mRecyclerView == null) return;
        RecyclerView.ViewHolder vh = mRecyclerView.findViewHolderForAdapterPosition(adapterPos);
        if (vh == null) {
            if (triesLeft > 0) {
                mRecyclerView.postDelayed(() -> highlightItemAt(adapterPos, triesLeft - 1), 100L);
            }
            return;
        }
        final View v = vh.itemView;
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(220L)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(220L).start())
                .start();
    }

    @Override
    public void initView() {
        super.initView();
        baseBind.toolbar.inflateMenu(R.menu.local_save);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_bookmark) {
                    FeatureEntity entity = new FeatureEntity();
                    entity.setUuid(userID + "漫画作品");
                    entity.setShowToolbar(showToolbar);
                    entity.setDataType("漫画作品");
                    entity.setIllustJson(Common.cutToJson(allItems));
                    entity.setUserID(userID);
                    entity.setDateTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).downloadDao().insertFeature(entity);
                    Common.showToast("已收藏到精华");
                    return true;
                }
                if (item.getItemId() == R.id.action_jump) {
                    UserIllustJumpHelper.showJumpDialog(
                            mActivity, userID, UserIllustJumpHelper.Kind.MANGA,
                            (offset, pickedDate) -> {
                                if (isAdded() && !isStateSaved()) {
                                    getParentFragmentManager().beginTransaction()
                                            .replace(getId(), newInstance(userID, showToolbar, offset, pickedDate))
                                            .commit();
                                }
                            });
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new UserMangaRepo(userID, initialOffset);
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        if (showToolbar) {
            return getString(R.string.string_233);
        } else {
            return super.getToolbarTitle();
        }
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}
