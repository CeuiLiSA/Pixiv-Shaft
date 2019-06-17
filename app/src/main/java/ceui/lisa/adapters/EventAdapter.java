package ceui.lisa.adapters;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * 已关注用户的 动态（最新作品）
 */
public class EventAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<IllustsBean> allIllust;
    private int imageSize = 0;

    public EventAdapter(List<IllustsBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = mContext.getResources().getDisplayMetrics().widthPixels;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_user_event, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
        params.height = imageSize * 2 / 3;
        params.width = imageSize;
        currentOne.illust.setLayoutParams(params);
        currentOne.userName.setText(allIllust.get(position).getUser().getName());
        if (!TextUtils.isEmpty(allIllust.get(position).getCreate_date())) {
            currentOne.publistDate.setText(allIllust.get(position).getCreate_date().substring(0, 16));
        }

        if(allIllust.get(position).isIs_bookmarked()){
            currentOne.star.setText("取消收藏");
        }else {
            currentOne.star.setText("收藏");
        }

        Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                .getUser().getProfile_image_urls().getMedium())).into(currentOne.head);
        Glide.with(mContext).load(GlideUtil.getLargeImage(allIllust.get(position)))
                .placeholder(R.color.light_bg)
                .into(currentOne.illust);
        if(mOnItemClickListener != null){
            currentOne.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
            currentOne.head.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 1));
            currentOne.download.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 2));
            currentOne.star.setOnClickListener(v -> {
                if(allIllust.get(position).isIs_bookmarked()){
                    currentOne.star.setText("收藏");
                }else {
                    currentOne.star.setText("取消收藏");
                }
                mOnItemClickListener.onItemClick(v, position, 3);
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

    public static class TagHolder extends RecyclerView.ViewHolder {
        ImageView illust;
        TextView userName, publistDate;
        CircleImageView head;
        Button download, star;
        TagHolder(View itemView) {
            super(itemView);
            head = itemView.findViewById(R.id.user_head);
            illust = itemView.findViewById(R.id.illust_image);
            userName = itemView.findViewById(R.id.user_name);
            publistDate = itemView.findViewById(R.id.illust_date);
            download = itemView.findViewById(R.id.download);
            star = itemView.findViewById(R.id.star);
        }
    }
}
