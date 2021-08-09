package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.models.TagsBean;

//自己收藏的Tag
public class BookedTagAdapter extends BaseAdapter<TagsBean, RecyBookTagBinding> {

    private boolean isMuted;

    public BookedTagAdapter(List<TagsBean> targetList, Context context, boolean muted) {
        super(targetList, context);
        isMuted = muted;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_book_tag;
    }

    @Override
    public void bindData(TagsBean target, ViewHolder<RecyBookTagBinding> bindView, int position) {
        if (TextUtils.isEmpty(allItems.get(position).getName())) {
            bindView.baseBind.starSize.setText(R.string.string_155);
        } else {
            if (!TextUtils.isEmpty(allItems.get(position).getTranslated_name())) {
                bindView.baseBind.starSize.setText(String.format("#%s/%s", allItems.get(position).getName(), allItems.get(position).getTranslated_name()));
            } else {
                bindView.baseBind.starSize.setText(String.format("#%s", allItems.get(position).getName()));
            }
        }

        if (allItems.get(position).getCount() == -1) {
            bindView.baseBind.illustCount.setText("");
        } else {
            if (isMuted) {
                bindView.baseBind.illustCount.setText(R.string.string_157);
                bindView.baseBind.illustCount.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mOnItemClickListener.onItemClick(v, position, 1);
                    }
                });
            } else {
                bindView.baseBind.illustCount.setText(mContext.getString(R.string.string_156, allItems.get(position).getCount()));
            }
        }
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
