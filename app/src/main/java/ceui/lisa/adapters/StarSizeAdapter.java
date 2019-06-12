package ceui.lisa.adapters;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.response.UserPreviewsBean;
import ceui.lisa.utils.GlideUtil;
import de.hdodenhof.circleimageview.CircleImageView;


/**
 * 推荐用户
 */
public class StarSizeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private String[] allIllust;

    public StarSizeAdapter(String[] list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_star_size, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        currentOne.title.setText(allIllust[position]);
        if(mOnItemClickListener != null){
            holder.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }

    @Override
    public int getItemCount() {
        return allIllust.length;
    }

    public void setOnItemClickListener(OnItemClickListener itemClickListener) {
        mOnItemClickListener = itemClickListener;
    }

    public static class TagHolder extends RecyclerView.ViewHolder {
        CheckBox title;
        TagHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.star_size);
        }
    }
}
