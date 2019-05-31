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
import ceui.lisa.response.ArticalResponse;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.GlideUtil;


/**
 * 推荐用户
 */
public class PivisionHorizontalAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<ArticalResponse.SpotlightArticlesBean> allIllust;

    public PivisionHorizontalAdapter(List<ArticalResponse.SpotlightArticlesBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_artical_horizon, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;


        currentOne.title.setText(allIllust.get(position).getTitle());
        Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                .getThumbnail())).into(currentOne.imageView);
        if (mOnItemClickListener != null) {
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
        ImageView imageView;
        TextView title;
        TagHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.illust_image);
            title = itemView.findViewById(R.id.title);
        }
    }
}
