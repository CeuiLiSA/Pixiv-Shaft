package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;
import android.widget.CompoundButton;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecySelectTagBinding;
import ceui.lisa.models.TagsBean;

public class SAdapter extends BaseAdapter<TagsBean, RecySelectTagBinding> {

    public SAdapter(List<TagsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_select_tag;
    }

    @Override
    public void bindData(TagsBean target, ViewHolder<RecySelectTagBinding> bindView, int position) {
        String tagName = allIllust.get(position).getName();
        String translatedTagName = allIllust.get(position).getTranslated_name();
        String finalTagName = tagName;
        if (!TextUtils.isEmpty(translatedTagName)) {
            finalTagName = String.format("%s/%s", tagName, translatedTagName);
        }
        bindView.baseBind.starSize.setText(finalTagName);

        bindView.baseBind.illustCount.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                allIllust.get(position).setSelectedLocalAndRemote(isChecked);
            }
        });

        bindView.baseBind.illustCount.setChecked(allIllust.get(position).isSelectedLocalOrRemote());
        bindView.itemView.setOnClickListener(v -> bindView.baseBind.illustCount.performClick());
    }
}
