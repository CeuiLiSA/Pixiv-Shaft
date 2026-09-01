package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ceui.lisa.activities.ImageDetailActivity;
import ceui.pixiv.api.model.Illust;

public abstract class AbstractIllustAdapter<VH extends RecyclerView.ViewHolder>
        extends RecyclerView.Adapter<VH> {

    protected Illust allIllust;
    protected Context mContext;
    protected int imageSize;
    protected boolean isForceOriginal;

    /**
     * 快照模式：非 null 表示这份 adapter 渲染的是离线快照。
     * 影响两件事:点击大图走 ImageDetailActivity 的「快照大图」分支;
     * 以及 {@link IllustAdapter#scanLocalDownloads()} 整个停摆——快照页只许显示快照自带的文件。
     * 构造之后才由宿主在主线程赋值,后台扫描线程会读它,故 volatile。
     */
    protected volatile String snapshotId = null;

    protected volatile boolean snapshotIsAuto = false;

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public void setSnapshotIsAuto(boolean snapshotIsAuto) {
        this.snapshotIsAuto = snapshotIsAuto;
    }

    /**
     * 用最新的 bean 顶掉持有的旧引用，不 notify、不碰视图。
     *
     * <p>给「渲染输入没变、所以不重建 adapter」的调用方用（见 FragmentIllust 的
     * imageAreaSignature，#962）：ObjectPool 合并更新时会产出新实例，adapter 抓着建它那一刻的
     * 旧实例不放，长按下载的收藏态判断（{@code !allIllust.isBookmarked()}）和跳二级详情带的
     * extra 就会读到过期值。前提是调用方已确认图片相关字段完全相同，所以这里只换引用。
     */
    public void rebindIllust(Illust illust) {
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
            intent.putExtra("dataType", snapshotId != null ? "快照大图" : "二级详情");
            if (snapshotId != null) {
                intent.putExtra(ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_ID, snapshotId);
                intent.putExtra(ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, snapshotIsAuto);
            }
            intent.putExtra("index", position);
            // 点击处的屏幕矩形:大图页(透明窗口)从这里展开进场,下拉收掉时缩回同一位置
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            intent.putExtra(ImageDetailActivity.EXTRA_ENTER_BOUNDS, new int[]{
                    loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()});
            mContext.startActivity(intent);
        });
    }
}
