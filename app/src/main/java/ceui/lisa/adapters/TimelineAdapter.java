package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.RecyTimelineIllustBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.MetaPagesBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.loxia.DateParse;

public class TimelineAdapter extends BaseAdapter<IllustsBean, RecyTimelineIllustBinding> {

    public TimelineAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
        handleClick();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_timeline_illust;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyTimelineIllustBinding> bindView, int position) {
        // ── Header ──
        if (target.getUser() != null) {
            bindView.baseBind.userName.setText(target.getUser().getName());
            GlideUrl userIconUrl = GlideUtil.getHead(target.getUser());
            if (userIconUrl != null) {
                Glide.with(mContext)
                        .load(userIconUrl)
                        .into(bindView.baseBind.userIcon);
            }
        }
        bindView.baseBind.postTime.setText(DateParse.INSTANCE.getTimeAgo(mContext, target.getCreate_date()));

        // ── Title ──
        String title = target.getTitle();
        if (title != null && !title.isEmpty()) {
            bindView.baseBind.title.setVisibility(View.VISIBLE);
            bindView.baseBind.title.setText(title);
        } else {
            bindView.baseBind.title.setVisibility(View.GONE);
        }

        // ── Image area ──
        int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        float density = mContext.getResources().getDisplayMetrics().density;
        // image container width = screen - 16dp*2 (horizontal margin on the CardView)
        int imageWidth = screenWidth - (int) (32 * density);

        boolean isMulti = target.getPage_count() > 1
                && target.getMeta_pages() != null
                && !target.getMeta_pages().isEmpty();

        if (isMulti) {
            bindView.baseBind.illustImage.setVisibility(View.GONE);
            bindView.baseBind.imageGrid.setVisibility(View.VISIBLE);

            List<MetaPagesBean> pages = target.getMeta_pages();
            int total = pages.size();
            int spanCount = total <= 4 ? 2 : 3;
            int maxShow = spanCount == 2 ? 4 : 9;
            int showCount = Math.min(total, maxShow);
            boolean hasMore = total > maxShow;
            int remaining = total - maxShow;
            int cellSize = (imageWidth - (int) ((spanCount - 1) * 2 * density)) / spanCount;

            bindView.baseBind.imageGrid.setLayoutManager(new GridLayoutManager(mContext, spanCount));
            View.OnClickListener gridClick = view -> {
                if (mOnItemClickListener != null) {
                    mOnItemClickListener.onItemClick(view, position, 0);
                }
            };
            bindView.baseBind.imageGrid.setAdapter(
                    new GridImageAdapter(mContext, target, showCount, cellSize, hasMore, remaining, gridClick));
        } else {
            bindView.baseBind.illustImage.setVisibility(View.VISIBLE);
            bindView.baseBind.imageGrid.setVisibility(View.GONE);

            // Aspect ratio clamped to [0.6, 1.5] like Compose
            float ratio = target.getWidth() > 0
                    ? (float) target.getHeight() / (float) target.getWidth()
                    : 1.0f;
            ratio = Math.max(0.6f, Math.min(ratio, 1.5f));
            int imgHeight = (int) (imageWidth * ratio);

            ViewGroup.LayoutParams imgParams = bindView.baseBind.illustImage.getLayoutParams();
            imgParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            imgParams.height = imgHeight;
            bindView.baseBind.illustImage.setLayoutParams(imgParams);

            GlideUrl imgUrl = GlideUtil.getLargeImage(target);
            Glide.with(mContext)
                    .load(imgUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(bindView.baseBind.illustImage);
        }

        // ── Stats overlay ──
        bindView.baseBind.viewCount.setText(String.format(Locale.getDefault(), "%,d", target.getTotal_view()));
        bindView.baseBind.bookmarkCount.setText(String.format(Locale.getDefault(), "%,d", target.getTotal_bookmarks()));

        // ── Page count badge ──
        if (target.getPage_count() > 1) {
            bindView.baseBind.pageCountBadge.setVisibility(View.VISIBLE);
            bindView.baseBind.pageCount.setText(String.valueOf(target.getPage_count()));
        } else {
            bindView.baseBind.pageCountBadge.setVisibility(View.GONE);
        }

        // ── Click ──
        bindView.baseBind.card.setOnClickListener(view -> {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(view, position, 0);
            }
        });
    }

    private void handleClick() {
        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                final PageData pageData = new PageData(uuid, nextUrl, allItems);
                Container.get().addPageToMap(pageData);

                Intent intent = new Intent(mContext, VActivity.class);
                intent.putExtra(Params.POSITION, position);
                intent.putExtra(Params.PAGE_UUID, uuid);
                mContext.startActivity(intent);
            }
        });
    }

    private static class GridImageAdapter extends RecyclerView.Adapter<GridImageAdapter.VH> {

        private final Context context;
        private final IllustsBean illust;
        private final int showCount;
        private final int cellSize;
        private final boolean hasMore;
        private final int remaining;
        private final View.OnClickListener itemClickListener;

        GridImageAdapter(Context context, IllustsBean illust, int showCount,
                         int cellSize, boolean hasMore, int remaining,
                         View.OnClickListener itemClickListener) {
            this.context = context;
            this.illust = illust;
            this.showCount = showCount;
            this.cellSize = cellSize;
            this.hasMore = hasMore;
            this.remaining = remaining;
            this.itemClickListener = itemClickListener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context)
                    .inflate(R.layout.item_timeline_grid_image, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.itemView.getLayoutParams().height = cellSize;

            GlideUrl url = GlideUtil.getLargeImage(illust, position);
            Glide.with(context)
                    .load(url)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(holder.image);

            boolean isLast = position == showCount - 1;
            if (isLast && hasMore) {
                holder.overlay.setVisibility(View.VISIBLE);
                holder.moreCount.setText(String.format(Locale.getDefault(), "+%d", remaining));
            } else {
                holder.overlay.setVisibility(View.GONE);
            }
            holder.itemView.setOnClickListener(itemClickListener);
        }

        @Override
        public int getItemCount() {
            return showCount;
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView image;
            final FrameLayout overlay;
            final TextView moreCount;

            VH(@NonNull View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.grid_image);
                overlay = itemView.findViewById(R.id.overlay);
                moreCount = itemView.findViewById(R.id.more_count);
            }
        }
    }
}
