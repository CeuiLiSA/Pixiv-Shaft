package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.Target;

import org.sufficientlysecure.htmltextview.HtmlAssetsImageGetter;
import org.sufficientlysecure.htmltextview.HtmlHttpImageGetter;
import org.sufficientlysecure.htmltextview.HtmlHttpImageGetter.UrlDrawable;
import org.xml.sax.XMLReader;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyCommentListBinding;
import ceui.lisa.models.CommentsBean;
import ceui.lisa.utils.Emoji;
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
        Glide.with(mContext).load(GlideUtil.getHead(allIllust.get(position).getUser()))
                .into(bindView.baseBind.userHead);
        bindView.baseBind.userName.setText(allIllust.get(position).getUser().getName());
        bindView.baseBind.time.setText(allIllust.get(position).getDate());
        bindView.baseBind.content.setHtml(allIllust.get(position).getComment(),
                new HtmlAssetsImageGetter(bindView.baseBind.content));

        if (allIllust.get(position).getParent_comment() != null &&
                allIllust.get(position).getParent_comment().getUser() != null) {
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

            SpannableString spannableString = new SpannableString(Html.fromHtml(String.format("@%s：%s",
                    allIllust.get(position).getParent_comment().getUser().getName(),
                    allIllust.get(position).getParent_comment().getComment()),
                    new HtmlAssetsImageGetter(bindView.baseBind.content), null));
            spannableString.setSpan(clickableSpan,
                    0, allIllust.get(position).getParent_comment().getUser().getName().length() + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bindView.baseBind.replyContent.setMovementMethod(LinkMovementMethod.getInstance());
            bindView.baseBind.replyContent.setText(spannableString);
        } else {
            bindView.baseBind.replyComment.setVisibility(View.GONE);
        }


        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v ->
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
