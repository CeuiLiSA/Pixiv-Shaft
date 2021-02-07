package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.CompoundButton;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.databinding.RecyMutedTagBinding;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.PixivOperate;

//自己收藏的Tag
public class MutedTagAdapter extends BaseAdapter<TagsBean, RecyMutedTagBinding> {

    public MutedTagAdapter(List<TagsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_muted_tag;
    }

    @Override
    public void bindData(TagsBean target, ViewHolder<RecyMutedTagBinding> bindView, int position) {
        if (TextUtils.isEmpty(allIllust.get(position).getName())) {
            bindView.baseBind.starSize.setText(R.string.string_155);
        } else {
            if (!TextUtils.isEmpty(allIllust.get(position).getTranslated_name())) {
                bindView.baseBind.starSize.setText(String.format("#%s/%s", allIllust.get(position).getName(), allIllust.get(position).getTranslated_name()));
            } else {
                bindView.baseBind.starSize.setText(String.format("#%s", allIllust.get(position).getName()));
            }
        }

        bindView.baseBind.isEffective.setOnCheckedChangeListener(null);
        bindView.baseBind.isEffective.setChecked(target.isEffective());
        bindView.baseBind.isEffective.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                target.setEffective(isChecked);
                PixivOperate.updateTag(target);
            }
        });

        if (mOnItemClickListener != null) {
            bindView.baseBind.deleteItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(v, position, 1);
                }
            });
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
