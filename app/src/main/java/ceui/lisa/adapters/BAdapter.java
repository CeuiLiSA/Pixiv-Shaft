package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.databinding.RecyCommentListBinding;
import ceui.lisa.model.BookmarkTagsBean;
import ceui.lisa.model.CommentsBean;
import ceui.lisa.utils.GlideUtil;

/**
 * 评论列表
 */

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
