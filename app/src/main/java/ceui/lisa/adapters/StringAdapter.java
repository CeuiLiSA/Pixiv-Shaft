package ceui.lisa.adapters;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyStringBinding;

public class StringAdapter extends BaseAdapter<String, RecyStringBinding> {


    public StringAdapter(@Nullable List<String> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_string;
    }


    @Override
    public void bindData(String target, ViewHolder<RecyStringBinding> bindView, int position) {
        bindView.baseBind.content.setText(target);
    }
}
