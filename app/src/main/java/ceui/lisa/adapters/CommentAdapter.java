package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Color;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;
import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.response.CommentsBean;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import de.hdodenhof.circleimageview.CircleImageView;


/**
 *
 */
public class CommentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<CommentsBean> allIllust;

    public CommentAdapter(List<CommentsBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_comment_list, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;

        Glide.with(mContext).load(GlideUtil.getHead(allIllust.get(position).getUser()))
                .into(currentOne.mCircleImageView);
        currentOne.mTextView.setText(allIllust.get(position).getUser().getName());
        currentOne.mTextView2.setText(allIllust.get(position).getDate());
        currentOne.mTextView3.setText("1087");
        currentOne.mTextView4.setText(allIllust.get(position).getComment());

        if(allIllust.get(position).getParent_comment() != null &&
                allIllust.get(position).getParent_comment().getUser() != null){
            currentOne.mRelativeLayout.setVisibility(View.VISIBLE);
            SpannableString spannableString = new SpannableString(String.format("@%s：%s",
                    allIllust.get(position).getParent_comment().getUser().getName(),
                    allIllust.get(position).getParent_comment().getComment()));
            spannableString.setSpan(new ForegroundColorSpan(Color.parseColor("#507daf")),
                    0, allIllust.get(position).getParent_comment().getUser().getName().length() + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ((TagHolder) holder).mTextView5.setText(spannableString);
        }else {
            currentOne.mRelativeLayout.setVisibility(View.GONE);
        }



        if(mOnItemClickListener != null){
            holder.itemView.setOnClickListener(v -> {
                mOnItemClickListener.onItemClick(v, position, 0);
            });
        }
    }

    @Override
    public int getItemCount() {
        return allIllust.size();
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        mOnItemClickListener = itemClickListener;
    }

    public class TagHolder extends RecyclerView.ViewHolder {
        private RelativeLayout mRelativeLayout;
        private TextView mTextView, mTextView2, mTextView3, mTextView4, mTextView5;
        private CircleImageView mCircleImageView;

        TagHolder(View itemView) {
            super(itemView);

            mCircleImageView = itemView.findViewById(R.id.user_head);
            mTextView = itemView.findViewById(R.id.user_name);
            mTextView2 = itemView.findViewById(R.id.time);
            mTextView3 = itemView.findViewById(R.id.like_count);
            mTextView4 = itemView.findViewById(R.id.content);
            mTextView5 = itemView.findViewById(R.id.reply_content);
            mRelativeLayout = itemView.findViewById(R.id.reply_comment);
        }
    }
}
