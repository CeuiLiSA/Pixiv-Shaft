package ceui.lisa.fragments;

import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.Fragment;

import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.PageAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.Container;
import ceui.lisa.core.IDWithList;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.models.IllustsBean;

public class FragmentCurrent extends LocalListFragment<FragmentBaseListBinding, IDWithList<IllustsBean>> {

    public static Fragment newInstance() {
        return new FragmentCurrent();
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new PageAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new LocalRepo<List<PageData>>() {
            @Override
            public List<PageData> first() {
                return Container.get().getAll();
            }

            @Override
            public List<PageData> next() {
                return null;
            }
        };
    }
}
