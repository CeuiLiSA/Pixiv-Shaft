package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.target.Target;
import ceui.pixiv.witstudio.theme.WitDisplay;
import ceui.pixiv.witstudio.popup.WitPopup;
import ceui.pixiv.witstudio.popup.WitPopups;

import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.pixiv.ui.muted.MuteTagSheet;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.interfaces.OnItemLongClickListener;
import ceui.loxia.Illust;
import ceui.loxia.UserModelConverter;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.db.EntityWrapper;
import ceui.pixiv.ui.recommend.TrendingScoreFormatKt;
import ceui.pixiv.ui.slideshow.SlideshowLauncher;

public class IAdapter extends BaseAdapter<Illust, RecyIllustStaggerBinding> implements MultiDownload {

    private static final float MIN_HEIGHT_RATIO = 0.6f;
    private static final float MAX_HEIGHT_RATIO = 2.0f;

    // 只为绑定时实时算列宽用：LayoutManager 的 measure 先于绑定，旋转后拿到的已是新方向的宽度
    private RecyclerView attachedRecyclerView;

    public IAdapter(List<Illust> targetList, Context context) {
        super(targetList, context);
        handleClick();
        handleLongClick();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null;
        }
    }

    /**
     * 当前每列的实际宽度。Glide 请求尺寸必须显式给：into(ImageView) 对 centerCrop 会在
     * 解码阶段按「请求尺寸」的宽高比裁位图，而默认请求尺寸取复用卡片上一次布局残留的旧宽高
     * （旧方向的列宽 × 上一张图的比例），横竖屏来回切后图会被裁得只剩一小块还发糊，且 view
     * 重新量高后 Glide 不会重发请求。
     */
    private int currentColumnWidth() {
        int listWidth = 0;
        if (attachedRecyclerView != null && attachedRecyclerView.getLayoutManager() != null) {
            listWidth = attachedRecyclerView.getLayoutManager().getWidth();
        }
        if (listWidth <= 0) {
            listWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        }
        return Math.max(1, listWidth / Shaft.sSettings.getLineCount());
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_illust_stagger;
    }

    @Override
    public void bindData(Illust target, ViewHolder<RecyIllustStaggerBinding> bindView, int position) {

        // 只按元数据驱动宽高比（钳到宽的 0.6~2.0 倍），宽度交给瀑布流列自身，
        // DynamicHeightImageView 在 onMeasure 用真实列宽算高——绝不写死像素尺寸，
        // 否则复用卡片在横竖屏切换后揣着旧方向的尺寸把整列搞乱
        float ratio;
        if (target.getWidth() > 0 && target.getHeight() > 0) {
            ratio = Math.max(MIN_HEIGHT_RATIO, Math.min(MAX_HEIGHT_RATIO,
                    (float) target.getHeight() / (float) target.getWidth()));
        } else {
            ratio = 1f;
        }
        bindView.baseBind.illustImage.setHeightRatio(ratio);
//        bindView.baseBind.debugMessage.setText("宽：" + target.getWidth() + "高：" + target.getHeight() + "id: " + target.getId());

        renderLikeState(bindView.baseBind.likeButton, target.isBookmarked());
        bindView.baseBind.likeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean willBookmark = !target.isBookmarked();
                renderLikeState(bindView.baseBind.likeButton, willBookmark);
                if (Shaft.sSettings.isPrivateStar()) {
                    PixivOperate.postLike(target, Params.TYPE_PRIVATE, showRelated, (position + 2));
                } else {
                    PixivOperate.postLike(target, Params.TYPE_PUBLIC, showRelated, (position + 2));
                }
                // 收藏后自动下载只在用户主动收藏(非取消)时触发,避免和"下载时自动收藏"循环联动(issue #880)。
                if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar()) {
                    IllustDownload.downloadIllustAllPages(target);
                }
            }
        });
        bindView.baseBind.likeButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // 「按标签收藏」改走 MD3 bottom sheet（原先是从右侧 push 进来的整页 activity）。
                // 这里只有 Context，用 showFrom 解出宿主 FragmentActivity。
                SelectTagBottomSheet.showFrom(
                        mContext, (int) target.getId(), Params.TYPE_ILLUST,
                        target.getTagNames().toArray(new String[0]));
                return true;
            }
        });

        GlideUrl imgUrl = Shaft.sSettings.isShowLargeThumbnailImage() ? GlideUtil.getLargeImage(target) : GlideUtil.getMediumImg(target);
        RequestBuilder<Drawable> requestBuilder = Glide.with(mContext)
                .load(imgUrl);
//        if (ratio == MIN_HEIGHT_RATIO || ratio == MAX_HEIGHT_RATIO) {
//            requestBuilder
//                    .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
//                    .centerCrop()
//                    .placeholder(R.color.second_light_bg)
//                    .transition(DrawableTransitionOptions.withCrossFade())
//                    .error(getBuilder(target))
//                    .into(bindView.baseBind.illustImage);
//        } else {
//            requestBuilder
//                    .fitCenter()
//                    .placeholder(R.color.second_light_bg)
//                    .transition(DrawableTransitionOptions.withCrossFade())
//                    .error(getBuilder(target))
//                    .into(bindView.baseBind.illustImage);
//        }
        // 请求宽高比恒等于展示宽高比（当前列宽 × 钳制后比例），与复用卡片残留的旧尺寸解耦
        int columnWidth = currentColumnWidth();
        int columnHeight = (int) (columnWidth * ratio);
        requestBuilder
                .override(columnWidth, columnHeight)
                .placeholder(ceui.pixiv.feeds.R.color.feed_skeleton_block)
                .transition(DrawableTransitionOptions.withCrossFade())
                .error(getBuilder(target).override(columnWidth, columnHeight))
                .into(bindView.baseBind.illustImage);

        if (target.getPage_count() == 1) {
            bindView.baseBind.pSize.setVisibility(View.GONE);
        } else {
            bindView.baseBind.pSize.setVisibility(View.VISIBLE);
            bindView.baseBind.pSize.setText(String.format(Locale.getDefault(), "%dP", target.getPage_count()));
        }
        bindView.baseBind.pGif.setVisibility(target.isGif() ? View.VISIBLE : View.GONE);
        bindView.baseBind.r18Badge.setVisibility(target.isR18File() ? View.VISIBLE : View.GONE);
        // 站长推荐场景:trending repo 把 score 注入 bean。其他 fragment 复用此
        // adapter 时 trendingScore=null,bindTrendingScore 内部走 GONE,无副作用。
        TrendingScoreFormatKt.bindTrendingScore(bindView.baseBind.trendingScore, target.getTrendingScore());
        bindView.itemView.setOnClickListener(view -> {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(view, position, 0);
            }
        });
        bindView.itemView.setOnLongClickListener(view -> {
            if(mOnItemLongClickListener != null){
                mOnItemLongClickListener.onItemLongClick(view, position, 0);
                return true;
            }
            return false;
        });
        if (target.isRelated()) {
            bindView.baseBind.pRelated.setVisibility(View.VISIBLE);
        } else {
            bindView.baseBind.pRelated.setVisibility(View.GONE);
        }
        if (target.isCreatedByAI()) {
            bindView.baseBind.createdByAi.setVisibility(View.VISIBLE);
        } else {
            bindView.baseBind.createdByAi.setVisibility(View.GONE);
        }
    }

    public RequestBuilder<Drawable> getBuilder(Illust target) {
        GlideUrl imgUrl = Shaft.sSettings.isShowLargeThumbnailImage() ? GlideUtil.getLargeImage(target) : GlideUtil.getMediumImg(target);
        return Glide.with(mContext)
                .load(imgUrl)
                .placeholder(ceui.pixiv.feeds.R.color.feed_skeleton_block)
                .transition(DrawableTransitionOptions.withCrossFade());
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<Illust> getIllustList() {
        return allItems;
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

    private void handleLongClick() {
        setOnItemLongClickListener(new OnItemLongClickListener() {
            @Override
            public void onItemLongClick(View v, int position, int viewType) {
                Illust illust = allItems.get(position);
                View popView = View.inflate(mContext, R.layout.pop_window_2, null);

                WitPopup mNormalPopup = WitPopups.popup(mContext)
                        .preferredDirection(WitPopup.DIRECTION_BOTTOM)
                        .view(popView)
                        .dimAmount(0.5f)
                        .edgeProtection(WitDisplay.dp2px(mContext, 20))
                        .offsetX(WitDisplay.dp2px(mContext, 20))
                        .offsetYIfBottom(WitDisplay.dp2px(mContext, 5))
                        .shadow(true)
                        .arrow(true)
                        .bgColor(mContext.getResources().getColor(R.color.fragment_center))
                        .animStyle(WitPopup.ANIM_GROW_FROM_RIGHT)
                        .onDismiss(new PopupWindow.OnDismissListener() {
                            @Override
                            public void onDismiss() {
                            }
                        })
                        .show(v);

                popView.findViewById(R.id.mute_setting).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        MuteTagSheet.show(
                                ((FragmentActivity) mContext).getSupportFragmentManager(),
                                illust.getTags() == null ? null : UserModelConverter.toTagsBeans(illust.getTags()),
                                illust.getUser() == null ? null : UserModelConverter.toUserBean(illust.getUser()));
                        mNormalPopup.dismiss();
                    }
                });
                popView.findViewById(R.id.batch_download).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startDownload();
                        mNormalPopup.dismiss();
                    }
                });
                popView.findViewById(R.id.download_this_one).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        IllustDownload.downloadIllustAllPages(illust);
                        if(Shaft.sSettings.isAutoPostLikeWhenDownload() && !illust.isBookmarked()){
                            PixivOperate.postLikeDefaultStarType(illust);
                        }
                        mNormalPopup.dismiss();
                    }
                });
                popView.findViewById(R.id.show_comment).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论");
                        // TemplateActivity 按 getIntExtra 读 ILLUST_ID,Illust.getId() 是 long 必须收窄
                        intent.putExtra(Params.ILLUST_ID, (int) illust.getId());
                        intent.putExtra(Params.ILLUST_TITLE, illust.getTitle());
                        mContext.startActivity(intent);
                        mNormalPopup.dismiss();
                    }
                });
                popView.findViewById(R.id.play_slideshow).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SlideshowLauncher.launchFromIllusts(mContext, allItems, position, true);
                        mNormalPopup.dismiss();
                    }
                });

                // 稍后再看:general_table 存 ceui.loxia.Illust,legacy 列表现在也是同一个模型,直接塞。
                // 文案按当前是否已在列表切换。
                EntityWrapper entityWrapper =
                        ((Shaft) mContext.getApplicationContext()).getEntityWrapper();
                boolean inWatchLater = entityWrapper.isInWatchLater(illust.getId());
                ((TextView) popView.findViewById(R.id.watch_later_toggle_text)).setText(
                        inWatchLater ? R.string.watch_later_remove : R.string.watch_later_add);
                popView.findViewById(R.id.watch_later_toggle).setOnClickListener(clickView -> {
                    if (inWatchLater) {
                        entityWrapper.removeFromWatchLater(mContext, illust.getId());
                        Common.showToast(R.string.watch_later_removed);
                    } else {
                        entityWrapper.addToWatchLater(mContext, illust);
                        Common.showToast(R.string.watch_later_added);
                    }
                    mNormalPopup.dismiss();
                });
            }
        });
    }

    /** 与 feeds 的 IllustFeedFragment.renderLikeState 同一套视觉：空心白 ↔ 实心红。 */
    private void renderLikeState(ImageView button, boolean liked) {
        button.setImageResource(liked
                ? R.drawable.ic_like_heart_fill : R.drawable.ic_like_heart_outline);
        button.setImageTintList(ColorStateList.valueOf(liked
                ? ContextCompat.getColor(mContext, R.color.has_bookmarked) : Color.WHITE));
    }

    private boolean showRelated = false;

    public boolean isShowRelated() {
        return showRelated;
    }

    public IAdapter setShowRelated(boolean showRelated) {
        this.showRelated = showRelated;
        return this;
    }
}
