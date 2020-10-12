package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.widget.Toolbar;
import androidx.databinding.ViewDataBinding;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.feature.FeatureEntity;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.RelatedIllustRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

/**
 * 相关插画
 */
public class FragmentRelatedIllust extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    private int illustID;
    private String mTitle;

    public static FragmentRelatedIllust newInstance(int id, String title) {
        Bundle args = new Bundle();
        args.putInt(Params.ILLUST_ID, id);
        args.putString(Params.ILLUST_TITLE, title);
        FragmentRelatedIllust fragment = new FragmentRelatedIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illustID = bundle.getInt(Params.ILLUST_ID);
        mTitle = bundle.getString(Params.ILLUST_TITLE);
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
                    entity.setUuid(illustID + "相关作品");
                    entity.setDataType("相关作品");
                    entity.setIllustID(illustID);
                    entity.setIllustTitle(mTitle);
                    entity.setIllustJson(Common.cutToJson(allItems));
                    entity.setDateTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(mContext).downloadDao().insertFeature(entity);
                    Common.showToast("已收藏到精华");
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new RelatedIllustRepo(illustID);
    }

    @Override
    public String getToolbarTitle() {
        return mTitle + getString(R.string.string_231);
    }
}
