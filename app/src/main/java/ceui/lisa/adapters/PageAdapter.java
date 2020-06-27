package ceui.lisa.adapters;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.core.IDWithList;
import ceui.lisa.databinding.RecyPageBinding;
import ceui.lisa.models.IllustsBean;

public class PageAdapter extends BaseAdapter<IDWithList<IllustsBean>, RecyPageBinding> {

    public PageAdapter(@Nullable List<IDWithList<IllustsBean>> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_page;
    }

    @Override
    public void bindData(IDWithList<IllustsBean> target, ViewHolder<RecyPageBinding> bindView, int position) {
        bindView.baseBind.uuid.setText(target.getUUID());
        bindView.baseBind.content.setText(target.getList().toString());
    }
}
