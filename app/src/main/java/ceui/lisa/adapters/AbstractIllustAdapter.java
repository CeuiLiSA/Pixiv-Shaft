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

    /**
     * 用最新的 bean 顶掉持有的旧引用，不 notify、不碰视图。
     *
     * <p>给「渲染输入没变、所以不重建 adapter」的调用方用（见 FragmentIllust 的
     * imageAreaSignature，#962）：ObjectPool 合并更新时会产出新实例，adapter 抓着建它那一刻的
     * 旧实例不放，长按下载的收藏态判断（{@code !allIllust.isIs_bookmarked()}）和跳二级详情带的
     * extra 就会读到过期值。前提是调用方已确认图片相关字段完全相同，所以这里只换引用。
     */
    public void rebindIllust(IllustsBean illust) {
        if (illust != null) {
            allIllust = illust;
        }
    }

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
