package ceui.lisa.adapters;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyDoingBinding;
import ceui.lisa.feature.worker.AbstractTask;

public class DoingAdapter extends BaseAdapter<AbstractTask, RecyDoingBinding>{

    public DoingAdapter(@Nullable List<AbstractTask> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_doing;
    }

    @Override
    public void bindData(AbstractTask target, ViewHolder<RecyDoingBinding> bindView, int position) {
        bindView.baseBind.name.setText(target.getName());
    }
}
