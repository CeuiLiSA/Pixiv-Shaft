package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.popup.QMUIPopup;
import com.qmuiteam.qmui.widget.popup.QMUIPopups;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.core.TimeRecord;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.dialogs.MuteDialog;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class IAdapter extends BaseAdapter<IllustsBean, RecyIllustStaggerBinding> implements MultiDownload {

    private static final int MIN_HEIGHT = 350;
    private static final int MAX_HEIGHT = 600;
    private int imageSize;

    public IAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
        initImageSize();
        handleClick();
    }

    private void initImageSize() {
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels) / Shaft.sSettings.getLineCount();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_illust_stagger;
    }


    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyIllustStaggerBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
        params.width = imageSize;
        params.height = target.getHeight() * imageSize / target.getWidth();

        if (Shaft.sSettings.getLineCount() == 2) {
            if (params.height < MIN_HEIGHT) {
                params.height = MIN_HEIGHT;
            } else if (params.height > MAX_HEIGHT) {
                params.height = MAX_HEIGHT;
            }
        }
        bindView.baseBind.illustImage.setLayoutParams(params);

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
                    PixivOperate.postLike(target, Params.TYPE_PRIVATE);
                } else {
                    PixivOperate.postLike(target, Params.TYPE_PUBLUC);
                }
            }
        });
        bindView.baseBind.likeButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.ILLUST_ID, target.getId());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                mContext.startActivity(intent);
                return true;
            }
        });

        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(target))
                .placeholder(R.color.second_light_bg)
                .transition(DrawableTransitionOptions.withCrossFade())
                .error(getBuilder(target))
                .into(bindView.baseBind.illustImage);

        if (target.getPage_count() == 1) {
            bindView.baseBind.pSize.setVisibility(View.GONE);
        } else {
            bindView.baseBind.pSize.setVisibility(View.VISIBLE);
            bindView.baseBind.pSize.setText(String.format("%dP", target.getPage_count()));
        }
        bindView.baseBind.pGif.setVisibility(target.isGif() ? View.VISIBLE : View.GONE);
        bindView.itemView.setOnClickListener(view -> {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(view, position, 0);
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

    public RequestBuilder<Drawable> getBuilder(IllustsBean illust) {
        return Glide.with(mContext)
                .load(GlideUtil.getMediumImg(illust))
                .placeholder(R.color.second_light_bg)
                .transition(DrawableTransitionOptions.withCrossFade());
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
                TimeRecord.start();

                final PageData pageData = new PageData(allIllust);
                Container.get().addPageToMap(pageData);

                Intent intent = new Intent(mContext, VActivity.class);
                intent.putExtra(Params.POSITION, position);
                intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                mContext.startActivity(intent);
            }
        });
    }

    private void handleLongClick(View v, IllustsBean illust) {
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
        popView.findViewById(R.id.download_this_one).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IllustDownload.downloadAllIllust(illust, mContext);
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
