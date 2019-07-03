package ceui.lisa.adapters;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.BookmarkTags;


/**
 * 选择标签
 */
public class SelectTagAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<BookmarkTags.BookmarkTagsBean> allIllust;

    public SelectTagAdapter(List<BookmarkTags.BookmarkTagsBean> list, Context context) {
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
        if(TextUtils.isEmpty(allIllust.get(position).getName())){
            currentOne.title.setText("#全部");
        }else {
            currentOne.title.setText("#" + allIllust.get(position).getName());
        }

        if(allIllust.get(position).getCount() == -1){
            currentOne.count.setText("");
        }else {
            currentOne.count.setText(allIllust.get(position).getCount() + "个作品");
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
        TextView title, count;
        TagHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.star_size);
            count = itemView.findViewById(R.id.illust_count);
        }
    }
}
