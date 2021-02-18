package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.models.IllustsBean;

public abstract class AbstractIllustAdapter<VH extends RecyclerView.ViewHolder>
        extends RecyclerView.Adapter<VH> {

    protected IllustsBean allIllust;
    protected Context mContext;
    protected int imageSize;
    protected boolean isForceOriginal;

    @Override
    public int getItemCount() {
        return allIllust.getPage_count();
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, ImageDetailActivity.class);
            intent.putExtra("illust", allIllust);
            intent.putExtra("dataType", "二级详情");
            intent.putExtra("index", position);
            mContext.startActivity(intent);
        });
    }
}
