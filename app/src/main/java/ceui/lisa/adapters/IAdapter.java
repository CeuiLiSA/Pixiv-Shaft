package ceui.lisa.adapters;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.popup.QMUIPopup;
import com.qmuiteam.qmui.widget.popup.QMUIPopups;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.dialogs.MuteDialog;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import jp.wasabeef.glide.transformations.BlurTransformation;

import static ceui.lisa.activities.Shaft.sUserModel;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

public class IAdapter extends BaseAdapter<IllustsBean, RecyIllustStaggerBinding> implements MultiDownload {

    private static final int MIN_HEIGHT = 350;
    private static final int MAX_HEIGHT = 600;
    private int imageSize;
    private boolean isSquare = false;
    private boolean showLikeButton = false;

    public IAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
        initImageSize();
        handleClick();
    }

    public IAdapter(List<IllustsBean> targetList, Context context, boolean paramSquare) {
        super(targetList, context);
        isSquare = paramSquare;
        initImageSize();
        handleClick();
    }

    private void initImageSize() {
        if (isSquare) {
            imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                    mContext.getResources().getDimensionPixelSize(R.dimen.tweenty_four_dp)) / 2;

            Common.showLog("iadapter " + mContext.getResources().getDisplayMetrics().widthPixels +
                    " " + imageSize + " " + mContext.getResources().getDimensionPixelSize(R.dimen.tweenty_four_dp));
        } else {
            imageSize = (mContext.getResources().getDisplayMetrics().widthPixels) / 2;
        }
        showLikeButton = Shaft.sSettings.isShowLikeButton();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_illust_stagger;
    }


    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyIllustStaggerBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();

        params.width = imageSize;

        if (isSquare) {
            params.height = imageSize;
        } else {
            params.height = target.getHeight() * imageSize / target.getWidth();

            if (params.height < MIN_HEIGHT) {
                params.height = MIN_HEIGHT;
            } else if (params.height > MAX_HEIGHT) {
                params.height = MAX_HEIGHT;
            }
        }
        bindView.baseBind.illustImage.setLayoutParams(params);

        if (showLikeButton) {
            bindView.baseBind.likeButton.setVisibility(View.VISIBLE);
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
                    PixivOperate.postLike(target, sUserModel, FragmentLikeIllust.TYPE_PUBLUC);
                }
            });
            bindView.baseBind.likeButton.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!target.isIs_bookmarked()) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(Params.ILLUST_ID, target.getId());
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                        mContext.startActivity(intent);
                    }
                    return true;
                }
            });

        } else {
            bindView.baseBind.likeButton.setVisibility(View.GONE);
        }

        if (target.isShield()) {
            bindView.baseBind.hide.setVisibility(View.VISIBLE);
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(target))
                    .apply(bitmapTransform(new BlurTransformation(5, 15)))
                    .into(bindView.baseBind.illustImage);
        } else {
            bindView.baseBind.hide.setVisibility(View.INVISIBLE);
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(target))
                    .placeholder(R.color.second_light_bg)
                    .into(bindView.baseBind.illustImage);
        }
        bindView.baseBind.hide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (target.isShield()) {
                    bindView.baseBind.hide.setVisibility(View.INVISIBLE);
                    Glide.with(mContext)
                            .load(GlideUtil.getMediumImg(target))
                            .placeholder(bindView.baseBind.illustImage.getDrawable())
                            .transition(DrawableTransitionOptions.withCrossFade(800))
                            .into(bindView.baseBind.illustImage);
                    target.setShield(false);
                }
            }
        });


        if (target.getPage_count() == 1) {
            bindView.baseBind.pSize.setVisibility(View.GONE);
        } else {
            bindView.baseBind.pSize.setVisibility(View.VISIBLE);
            bindView.baseBind.pSize.setText(target.getPage_count() + "P");
        }
        bindView.baseBind.pGif.setVisibility(target.isGif() ? View.VISIBLE : View.GONE);
        bindView.itemView.setOnClickListener(view -> {
            if (mOnItemClickListener != null) {
                if (target.isShield()) {
                    Common.showToast("屏蔽了还要看？");
                } else {
                    mOnItemClickListener.onItemClick(view, position, 0);
                }
            }
        });
        bindView.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                handleLongClick(v, target);
                return true;
            }
        });
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<IllustsBean> getIllustList() {
        return allIllust;
    }

    private void handleClick() {
        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                DataChannel.get().setIllustList(allIllust);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                mContext.startActivity(intent);
//                ((Activity)mContext).overridePendingTransition(R.anim.zoom_enter,
//                        R.anim.zoom_exit);
            }
        });
    }

    private void handleLongClick(View v, IllustsBean illust) {
        View popView = LayoutInflater.from(mContext).inflate(R.layout.pop_window_2, null);
        QMUIPopup mNormalPopup = QMUIPopups.popup(mContext, QMUIDisplayHelper.dp2px(getContext(), 250))
                .preferredDirection(QMUIPopup.DIRECTION_BOTTOM)
                .view(popView)
                .dimAmount(0.5f)
                .edgeProtection(QMUIDisplayHelper.dp2px(getContext(), 20))
                .offsetX(QMUIDisplayHelper.dp2px(getContext(), 80))
                .offsetYIfBottom(QMUIDisplayHelper.dp2px(getContext(), 5))
                .shadow(true)
                .arrow(true)
                .animStyle(QMUIPopup.ANIM_GROW_FROM_RIGHT)
                .onDismiss(new PopupWindow.OnDismissListener() {
                    @Override
                    public void onDismiss() {
                    }
                })
                .show(v);

        popView.findViewById(R.id.not_interested).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MuteDialog muteDialog = MuteDialog.newInstance(illust);
                muteDialog.show(((FragmentActivity) mContext).getSupportFragmentManager(), "MuteDialog");
                mNormalPopup.dismiss();
            }
        });
        popView.findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload();
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
}
