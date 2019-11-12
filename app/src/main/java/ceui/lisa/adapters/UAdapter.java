package ceui.lisa.adapters;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.interfaces.FullClickListener;
import ceui.lisa.model.UserPreviewsBean;
import ceui.lisa.utils.GlideUtil;

public class UAdapter extends BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> {

    private int imageSize;
    private FullClickListener mFullClickListener;

    public UAdapter(List<UserPreviewsBean> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                2 * mContext.getResources().getDimensionPixelSize(R.dimen.eight_dp)) / 3;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_user_preview;
    }

    @Override
    public void bindData(UserPreviewsBean target, ViewHolder<RecyUserPreviewBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.userShowOne.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        bindView.baseBind.userShowOne.setLayoutParams(params);
        bindView.baseBind.userShowTwo.setLayoutParams(params);
        bindView.baseBind.userShowThree.setLayoutParams(params);
        bindView.baseBind.userName.setText(allIllust.get(position).getUser().getName());
        if (allIllust.get(position).getIllusts() != null && allIllust.get(position).getIllusts().size() >= 3) {
            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                    .getUser().getProfile_image_urls().getMedium())).into(bindView.baseBind.userHead);
            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                    .getIllusts().get(0)))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.userShowOne);
            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                    .getIllusts().get(1)))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.userShowTwo);
            Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                    .getIllusts().get(2)))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.userShowThree);
        }

        bindView.baseBind.postLikeUser.setText(allIllust.get(position).getUser().isIs_followed() ?
                mContext.getString(R.string.post_unfollow) : mContext.getString(R.string.post_follow));

        if (mFullClickListener != null) {
            bindView.itemView.setOnClickListener(v ->
                    mFullClickListener.onItemClick(v, position, 0));

            bindView.baseBind.postLikeUser.setOnClickListener(v ->
                    mFullClickListener.onItemClick(bindView.baseBind.postLikeUser, position, 1));

            bindView.baseBind.postLikeUser.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    mFullClickListener.onItemLongClick(bindView.baseBind.postLikeUser, position, 1);
                    return true;
                }
            });
        }
    }

    public UAdapter setFullClickListener(FullClickListener fullClickListener) {
        mFullClickListener = fullClickListener;
        return this;
    }
}
