package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.target.Target;
import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.popup.QMUIPopup;
import com.qmuiteam.qmui.widget.popup.QMUIPopups;

import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.dialogs.MuteDialog;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.interfaces.OnItemLongClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.page.ScreenUtils;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class IAdapter extends BaseAdapter<IllustsBean, RecyIllustStaggerBinding> implements MultiDownload {

    private static final float MIN_HEIGHT_RATIO = 0.6f;
    private static final float MAX_HEIGHT_RATIO = 2.0f;

    public IAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
        handleClick();
        handleLongClick();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_illust_stagger;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyIllustStaggerBinding> bindView, int position) {

        float ratio = (float) target.getHeight() / (float) target.getWidth();
        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
        int itemWidth =
                (ScreenUtils.getDisplayMetrics().widthPixels - ScreenUtils.dpToPx(24)) / 2;
        params.width = itemWidth;
        if (ratio > MAX_HEIGHT_RATIO) {
            // 如果图片很窄，但是高度很高，则限制最大高度，是宽的 1.8 倍
            params.height = (int) (itemWidth * MAX_HEIGHT_RATIO);
        } else if (ratio < MIN_HEIGHT_RATIO) {
            params.height = (int) (itemWidth * MIN_HEIGHT_RATIO);
        } else {
            params.height = (int) ((itemWidth * (float) target.getHeight()) / (float) target.getWidth());
        }
        bindView.baseBind.illustImage.setLayoutParams(params);
//        bindView.baseBind.debugMessage.setText("宽：" + target.getWidth() + "高：" + target.getHeight() + "id: " + target.getId());

        if (target.isIs_bookmarked()) {
            bindView.baseBind.likeButton.setImageTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.has_bookmarked)));
        } else {
            bindView.baseBind.likeButton.setImageTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.not_bookmarked)));
        }
        bindView.baseBind.likeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (target.isIs_bookmarked()) {
                    bindView.baseBind.likeButton.setImageTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.not_bookmarked)));
                } else {
                    bindView.baseBind.likeButton.setImageTintList(
                            ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.has_bookmarked)));
                }
                if (Shaft.sSettings.isPrivateStar()) {
                    PixivOperate.postLike(target, Params.TYPE_PRIVATE, showRelated, (position + 2));
                } else {
                    PixivOperate.postLike(target, Params.TYPE_PUBLIC, showRelated, (position + 2));
                }
            }
        });
        bindView.baseBind.likeButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.ILLUST_ID, target.getId());
                intent.putExtra(Params.DATA_TYPE, Params.TYPE_ILLUST);
                intent.putExtra(Params.TAG_NAMES, target.getTagNames());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                mContext.startActivity(intent);
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
        requestBuilder
                .placeholder(R.color.second_light_bg)
                .transition(DrawableTransitionOptions.withCrossFade())
                .error(getBuilder(target))
                .into(bindView.baseBind.illustImage);

        if (target.getPage_count() == 1) {
            bindView.baseBind.pSize.setVisibility(View.GONE);
        } else {
            bindView.baseBind.pSize.setVisibility(View.VISIBLE);
            bindView.baseBind.pSize.setText(String.format(Locale.getDefault(), "%dP", target.getPage_count()));
        }
        bindView.baseBind.pGif.setVisibility(target.isGif() ? View.VISIBLE : View.GONE);
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

    public RequestBuilder<Drawable> getBuilder(IllustsBean target) {
        GlideUrl imgUrl = Shaft.sSettings.isShowLargeThumbnailImage() ? GlideUtil.getLargeImage(target) : GlideUtil.getMediumImg(target);
        return Glide.with(mContext)
                .load(imgUrl)
                .placeholder(R.color.second_light_bg)
                .transition(DrawableTransitionOptions.withCrossFade());
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<IllustsBean> getIllustList() {
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
                IllustsBean illust = allItems.get(position);
                View popView = View.inflate(mContext, R.layout.pop_window_2, null);

                QMUIPopup mNormalPopup = QMUIPopups.popup(mContext)
                        .preferredDirection(QMUIPopup.DIRECTION_BOTTOM)
                        .view(popView)
                        .dimAmount(0.5f)
                        .edgeProtection(QMUIDisplayHelper.dp2px(mContext, 20))
                        .offsetX(QMUIDisplayHelper.dp2px(mContext, 20))
                        .offsetYIfBottom(QMUIDisplayHelper.dp2px(mContext, 5))
                        .shadow(true)
                        .arrow(true)
                        .bgColor(mContext.getResources().getColor(R.color.fragment_center))
                        .animStyle(QMUIPopup.ANIM_GROW_FROM_RIGHT)
                        .onDismiss(new PopupWindow.OnDismissListener() {
                            @Override
                            public void onDismiss() {
                            }
                        })
                        .show(v);

                popView.findViewById(R.id.mute_setting).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        MuteDialog muteDialog = MuteDialog.newInstance(illust);
                        muteDialog.show(((FragmentActivity) mContext).getSupportFragmentManager(), "MuteDialog");
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
                        if(Shaft.sSettings.isAutoPostLikeWhenDownload() && !illust.isIs_bookmarked()){
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
                        intent.putExtra(Params.ILLUST_ID, illust.getId());
                        intent.putExtra(Params.ILLUST_TITLE, illust.getTitle());
                        mContext.startActivity(intent);
                        mNormalPopup.dismiss();
                    }
                });
            }
        });
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
