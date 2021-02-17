package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.models.IllustsBean;

public abstract class AbstractIllustAdapter<VH extends RecyclerView.ViewHolder>
        extends RecyclerView.Adapter<VH> {

    protected IllustsBean allIllust;
    protected FragmentActivity mActivity;
    protected int imageSize;
    protected boolean isForceOriginal;

    @Override
    public int getItemCount() {
        return allIllust.getPage_count();
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(mActivity, ImageDetailActivity.class);
            intent.putExtra("illust", allIllust);
            intent.putExtra("dataType", "二级详情");
            intent.putExtra("index", position);
            mActivity.startActivity(intent);
        });
    }
}
