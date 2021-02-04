package ceui.lisa.fragments;

import android.view.MenuItem;

import androidx.appcompat.widget.Toolbar;

import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.MuteWorksAdapter;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.models.TagsBean;

public class FragmentMutedObjects extends LocalListFragment<FragmentBaseListBinding, MuteEntity>
        implements Toolbar.OnMenuItemClickListener {

    @Override
    public LocalRepo<List<MuteEntity>> repository() {
        return new LocalRepo<List<MuteEntity>>() {
            @Override
            public List<MuteEntity> first() {
                return TagFilter.getMutedWorks();
            }

            @Override
            public List<MuteEntity> next() {
                return null;
            }
        };
    }

    @Override
    public BaseAdapter<MuteEntity, RecyViewHistoryBinding> adapter() {
        return new MuteWorksAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        return false;
    }
}
