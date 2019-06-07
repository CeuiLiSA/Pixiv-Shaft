package ceui.lisa.adapters;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.response.TrendingtagResponse;
import ceui.lisa.response.UserPreviewsBean;
import ceui.lisa.utils.GlideUtil;
import de.hdodenhof.circleimageview.CircleImageView;


/**
 * 推荐用户
 */
public class UserAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<UserPreviewsBean> allIllust;
    private int imageSize = 0;

    public UserAdapter(List<UserPreviewsBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                2 * mContext.getResources().getDimensionPixelSize(R.dimen.eight_dp))/3;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_user_preview, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        ViewGroup.LayoutParams params = currentOne.one.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        currentOne.one.setLayoutParams(params);
        currentOne.two.setLayoutParams(params);
        currentOne.three.setLayoutParams(params);
        currentOne.title.setText(allIllust.get(position).getUser().getName());
        if(allIllust.get(position).getIllusts() != null && allIllust.get(position).getIllusts().size() >= 3) {
            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                    .getUser().getProfile_image_urls().getMedium())).into(currentOne.head);
            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                    .getIllusts().get(0)))
                    .placeholder(R.color.light_bg)
                    .into(currentOne.one);
            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                    .getIllusts().get(1)))
                    .placeholder(R.color.light_bg)
                    .into(currentOne.two);
            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                    .getIllusts().get(2)))
                    .placeholder(R.color.light_bg)
                    .into(currentOne.three);
        }
        if(mOnItemClickListener != null){
            holder.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
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
        ImageView one, two, three;
        TextView title;
        CircleImageView head;
        TagHolder(View itemView) {
            super(itemView);
            head = itemView.findViewById(R.id.user_head);
            title = itemView.findViewById(R.id.user_name);
            one = itemView.findViewById(R.id.user_show_one);
            two = itemView.findViewById(R.id.user_show_two);
            three = itemView.findViewById(R.id.user_show_three);
        }
    }
}
