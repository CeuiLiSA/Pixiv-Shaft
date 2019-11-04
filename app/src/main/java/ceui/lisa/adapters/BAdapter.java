package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.model.BookmarkTagsBean;

public class BAdapter extends BaseAdapter<BookmarkTagsBean, RecyBookTagBinding> {


    public BAdapter(List<BookmarkTagsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_book_tag;
    }

    @Override
    public void bindData(BookmarkTagsBean target, ViewHolder<RecyBookTagBinding> bindView, int position) {
        if (TextUtils.isEmpty(allIllust.get(position).getName())) {
            bindView.baseBind.starSize.setText("#全部");
        } else {
            bindView.baseBind.starSize.setText("#" + allIllust.get(position).getName());
        }

        if (allIllust.get(position).getCount() == -1) {
            bindView.baseBind.illustCount.setText("");
        } else {
            bindView.baseBind.illustCount.setText(allIllust.get(position).getCount() + "个作品");
        }
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
