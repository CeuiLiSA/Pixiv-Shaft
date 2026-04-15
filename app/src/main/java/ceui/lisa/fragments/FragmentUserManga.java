package ceui.lisa.fragments;


import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.widget.Toolbar;

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

    private static final String ARG_INITIAL_OFFSET = "initial_offset";

    private int userID;
    private boolean showToolbar = false;
    private int initialOffset = 0;

    public static FragmentUserManga newInstance(int userID, boolean paramShowToolbar) {
        return newInstance(userID, paramShowToolbar, 0);
    }

    public static FragmentUserManga newInstance(int userID, boolean paramShowToolbar, int initialOffset) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        args.putInt(ARG_INITIAL_OFFSET, initialOffset);
        FragmentUserManga fragment = new FragmentUserManga();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        showToolbar = bundle.getBoolean(Params.FLAG);
        initialOffset = bundle.getInt(ARG_INITIAL_OFFSET, 0);
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
                            offset -> {
                                if (isAdded() && !isStateSaved()) {
                                    getParentFragmentManager().beginTransaction()
                                            .replace(getId(), newInstance(userID, showToolbar, offset))
                                            .commit();
                                }
                                return kotlin.Unit.INSTANCE;
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
