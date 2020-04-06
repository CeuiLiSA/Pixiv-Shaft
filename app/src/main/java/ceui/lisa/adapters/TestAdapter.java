package ceui.lisa.adapters;

import android.content.Context;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecySingleLineTextBinding;

public class TestAdapter extends BaseAdapter<String, RecySingleLineTextBinding> {

    public TestAdapter(List<String> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_single_line_text;
    }

    @Override
    public void bindData(String target, ViewHolder<RecySingleLineTextBinding> bindView, int position) {
        bindView.baseBind.tagTitle.setText(target);
    }
}
