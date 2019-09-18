package ceui.lisa.adapters;

import android.content.Context;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyKissBinding;

public class KissAdapter extends BaseAdapter<String, RecyKissBinding> {

    public KissAdapter(List<String> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_kiss;
    }

    @Override
    public void bindData(String target, ViewHolder<RecyKissBinding> bindView, int position) {
        bindView.baseBind.position.setText(allIllust.get(position));
    }
}
