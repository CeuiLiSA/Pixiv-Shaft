package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Color;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.core.ImgGetter;
import ceui.lisa.databinding.RecyCommentListBinding;
import ceui.lisa.models.CommentsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;

/**
 * 评论列表
 */

public class CommentAdapter extends BaseAdapter<CommentsBean, RecyCommentListBinding> {


    public CommentAdapter(List<CommentsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_comment_list;
    }

    @Override
    public void bindData(CommentsBean target, ViewHolder<RecyCommentListBinding> bindView, int position) {
        Glide.with(mContext).load(GlideUtil.getHead(allItems.get(position).getUser()))
                .into(bindView.baseBind.userHead);
        bindView.baseBind.userName.setText(allItems.get(position).getUser().getName());
        bindView.baseBind.time.setText(Common.getLocalYYYYMMDDHHMMSSString(allItems.get(position).getDate()));
        bindView.baseBind.content.setHtml(allItems.get(position).getComment(),
                new ImgGetter(bindView.baseBind.content));

        if (allItems.get(position).getParent_comment() != null &&
                allItems.get(position).getParent_comment().getUser() != null) {
            bindView.baseBind.replyComment.setVisibility(View.VISIBLE);

            ClickableSpan clickableSpan = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    mOnItemClickListener.onItemClick(widget, position, 3);
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(Color.parseColor("#507daf"));
                }
            };

            SpannableString spannableString;
            //如果getParent_comment是一个包含表情的comment，就用fromHtml
            if (allItems.get(position).getParent_comment().getComment().contains("_2sgsdWB")) {
                Common.showLog("Emoji.hasEmoji true " + position + allItems.get(position).getParent_comment().getComment());
                spannableString = new SpannableString(Html.fromHtml(String.format("@%s：%s",
                        allItems.get(position).getParent_comment().getUser().getName(),
                        allItems.get(position).getParent_comment().getComment()),
                        new ImgGetter(bindView.baseBind.replyContent), null));
            } else {
                Common.showLog("Emoji.hasEmoji false " + position + allItems.get(position).getParent_comment().getComment());
                spannableString = new SpannableString(String.format("@%s：%s",
                        allItems.get(position).getParent_comment().getUser().getName(),
                        allItems.get(position).getParent_comment().getComment()));
            }
            spannableString.setSpan(clickableSpan,
                    0, allItems.get(position).getParent_comment().getUser().getName().length() + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bindView.baseBind.replyContent.setMovementMethod(LinkMovementMethod.getInstance());
            bindView.baseBind.replyContent.setText(spannableString);
        } else {
            bindView.baseBind.replyComment.setVisibility(View.GONE);
        }


        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v ->
                    mOnItemClickListener.onItemClick(v, position, 0));

            bindView.baseBind.content.setOnClickListener(v ->
                    mOnItemClickListener.onItemClick(v, position, 0));

            bindView.baseBind.userHead.setOnClickListener(v ->
                    mOnItemClickListener.onItemClick(v, position, 1));

            bindView.baseBind.userName.setOnClickListener(v ->
                    mOnItemClickListener.onItemClick(v, position, 1));

            bindView.baseBind.replyContent.setOnClickListener(v ->
                    mOnItemClickListener.onItemClick(v, position, 2));
        }
    }
}
