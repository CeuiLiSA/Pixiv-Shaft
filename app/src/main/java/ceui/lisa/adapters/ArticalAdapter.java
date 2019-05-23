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
import ceui.lisa.response.UserPreviewsBean;
import ceui.lisa.utils.GlideUtil;
import de.hdodenhof.circleimageview.CircleImageView;


public class ArticalAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<ArticalResponse.SpotlightArticlesBean> allIllust;
    private int imageSize = 0;

    public ArticalAdapter(List<ArticalResponse.SpotlightArticlesBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = mContext.getResources().getDisplayMetrics().widthPixels -
                2 * mContext.getResources().getDimensionPixelSize(R.dimen.sixteen_dp);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 10086) {
            View view = mLayoutInflater.inflate(R.layout.recy_artical_head, parent, false);
            return new HeadHolder(view);
        } else {
            View view = mLayoutInflater.inflate(R.layout.recy_artical, parent, false);
            return new TagHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if(position != 0) {
            final TagHolder currentOne = (TagHolder) holder;

            ViewGroup.LayoutParams params = currentOne.imageView.getLayoutParams();
            params.height = imageSize * 7 / 10;
            params.width = imageSize;
            currentOne.imageView.setLayoutParams(params);
            currentOne.title.setText(allIllust.get(position - 1).getTitle());

            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position - 1)
                    .getThumbnail())).into(currentOne.imageView);
            if (mOnItemClickListener != null) {
                holder.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position - 1, 0));
            }
        }
    }

    @Override
    public int getItemCount() {
        return allIllust.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return 10086;
        } else {
            return 12580;
        }
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

    public static class HeadHolder extends RecyclerView.ViewHolder {

        HeadHolder(View itemView) {
            super(itemView);
        }
    }
}
