package ceui.lisa.ui.fragment;

import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.FragmentQBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.fragments.LocalListFragment;
import ceui.lisa.interfaces.DataControl;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.IllustChannel;

public class FragmentT extends LocalListFragment<FragmentQBinding, IllustsBean, RecyIllustStaggerBinding> {

    @Override
    public DataControl<List<IllustsBean>> present() {
        return new DataControl<List<IllustsBean>>() {
            @Override
            public List<IllustsBean> first() {
                return IllustChannel.get().getIllustList();
            }

            @Override
            public List<IllustsBean> next() {
                return null;
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }
}
