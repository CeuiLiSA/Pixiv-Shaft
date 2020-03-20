package ceui.lisa.adapters;

import android.content.Context;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentSingleNovelBinding;

public class VAdapter extends BaseAdapter<String, FragmentSingleNovelBinding> {


    public VAdapter(List<String> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_single_novel;
    }

    @Override
    public void bindData(String target, ViewHolder<FragmentSingleNovelBinding> bindView, int position) {
        bindView.baseBind.novelDetail.setText(target);
    }
}
