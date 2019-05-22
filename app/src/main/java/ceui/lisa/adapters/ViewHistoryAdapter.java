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
import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.IllustEntity;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.GlideUtil;


/**
 *
 */
public class ViewHistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<IllustEntity> allIllust;
    private Gson mGson = new Gson();
    private int imageSize = 0;
    private SimpleDateFormat mTime = new SimpleDateFormat("MM月dd日 HH: mm");

    public ViewHistoryAdapter(List<IllustEntity> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp))/2;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_view_history, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        ViewGroup.LayoutParams params = currentOne.illust.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        IllustsBean currentIllust = mGson.fromJson(allIllust.get(position).getIllustJson(), IllustsBean.class);
        currentOne.illust.setLayoutParams(params);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(currentIllust))
                .placeholder(R.color.light_bg)
                .into(currentOne.illust);
        currentOne.title.setText(currentIllust.getTitle());
        currentOne.author.setText("by: " + currentIllust.getUser().getName());
        currentOne.time.setText(mTime.format(allIllust.get(position).getTime()));
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
        TextView title, time, author;
        TagHolder(View itemView) {
            super(itemView);
            illust = itemView.findViewById(R.id.illust_image);
            title = itemView.findViewById(R.id.title);
            time = itemView.findViewById(R.id.time);
            author = itemView.findViewById(R.id.author);
        }
    }
}
