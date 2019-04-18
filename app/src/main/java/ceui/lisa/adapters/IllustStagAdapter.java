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
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.GlideUtil;


/**
 *
 */
public class IllustStagAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<IllustsBean> allIllust;
    private int imageSize = 0;

    public IllustStagAdapter(List<IllustsBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels)/2;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_illust_stagger, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();

        params.width = imageSize;
        params.height = allIllust.get(position).getHeight() * imageSize / allIllust.get(position).getWidth();

        if(params.height < 300){
            params.height = 300;
        }else if(params.height > 500){
            params.height = 500;
        }
        currentOne.illust.setLayoutParams(params);
        currentOne.title.setText(allIllust.get(position).getTitle());
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(allIllust.get(position)))
                .placeholder(R.color.dark_bg)
                .into(currentOne.illust);
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
