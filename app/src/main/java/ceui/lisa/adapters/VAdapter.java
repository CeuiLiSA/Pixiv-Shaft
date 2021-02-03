package ceui.lisa.adapters;

import android.content.Context;
import android.view.View;

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
        if (position == 0) {
            if (target.contains("[chapter:")) {
                bindView.baseBind.chapter.setVisibility(View.VISIBLE);
                int start = target.indexOf("[chapter:") + 9;
                int end = target.indexOf("]") + 1;
                bindView.baseBind.chapter.setText(target.substring(start, end - 1));

                target = target.substring(end);
            } else {
                bindView.baseBind.chapter.setVisibility(View.GONE);
            }
            bindView.baseBind.head.setVisibility(View.VISIBLE);
        } else {
            bindView.baseBind.chapter.setVisibility(View.GONE);
            bindView.baseBind.head.setVisibility(View.GONE);
        }
        if (position == allIllust.size() - 1) {
            bindView.baseBind.bottom.setVisibility(View.VISIBLE);
            bindView.baseBind.endText.setVisibility(View.VISIBLE);
        } else {
            bindView.baseBind.bottom.setVisibility(View.GONE);
            bindView.baseBind.endText.setVisibility(View.GONE);
        }
        if (allIllust.size() == 1) {
            bindView.baseBind.partIndex.setVisibility(View.GONE);
        } else {
            bindView.baseBind.partIndex.setVisibility(View.VISIBLE);
            bindView.baseBind.partIndex.setText(String.format(" --- Part %d --- ", position + 1));
        }
        bindView.baseBind.novelDetail.setText(target);
    }
}
