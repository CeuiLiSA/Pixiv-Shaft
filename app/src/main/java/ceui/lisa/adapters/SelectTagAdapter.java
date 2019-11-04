package ceui.lisa.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.BookmarkTagsBean;


/**
 * 展示自己已收藏的标签 （可多选）
 */
public class SelectTagAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private OnItemClickListener mOnItemClickListener;
    private List<BookmarkTagsBean> allIllust;

    public SelectTagAdapter(List<BookmarkTagsBean> list, Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(mContext);
        allIllust = list;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.recy_select_tag, parent, false);
        return new TagHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final TagHolder currentOne = (TagHolder) holder;
        currentOne.title.setText(allIllust.get(position).getName());
        currentOne.count.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    allIllust.get(position).setSelected(true);
                } else {
                    allIllust.get(position).setSelected(false);
                }
            }
        });
        if (allIllust.get(position).isSelected()) {
            currentOne.count.setChecked(true);
        } else {
            currentOne.count.setChecked(false);
        }
        if (mOnItemClickListener != null) {
            holder.itemView.setOnClickListener(v -> {
                currentOne.count.performClick();
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
        TextView title;
        CheckBox count;

        TagHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.star_size);
            count = itemView.findViewById(R.id.illust_count);
        }
    }
}
