package ceui.lisa.adapters;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.TrendingtagResponse;
import ceui.lisa.utils.GlideUtil;


/**
 * 热门标签
 */
public class HotTagAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<TrendingtagResponse.TrendTagsBean> allIllust;
    private int imageSize = 0;

    public HotTagAdapter(List<TrendingtagResponse.TrendTagsBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.two_dp))/3;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_tag_grid, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;

        if(position == 0){
            ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
            params.height = imageSize * 2;
            params.width = imageSize * 3;
            currentOne.illust.setLayoutParams(params);
            Glide.with(mContext)
                    .load(GlideUtil.getLargeImage(allIllust.get(position).getIllust()))
                    .placeholder(R.color.light_bg)
                    .into(currentOne.illust);
        }else {
            ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
            params.height = imageSize;
            params.width = imageSize;
            currentOne.illust.setLayoutParams(params);
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(allIllust.get(position).getIllust()))
                    .placeholder(R.color.light_bg)
                    .into(currentOne.illust);
        }
        currentOne.title.setText(!TextUtils.isEmpty(allIllust.get(position).getTranslated_name()) ?
                allIllust.get(position).getTranslated_name() :
                allIllust.get(position).getTag());

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
        ImageView illust;
        TextView title;
        TagHolder(View itemView) {
            super(itemView);
            illust = itemView.findViewById(R.id.illust_image);
            title = itemView.findViewById(R.id.title);
        }
    }
}
