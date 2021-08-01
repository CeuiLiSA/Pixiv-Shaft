package ceui.lisa.adapters;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.interfaces.FullClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class UAdapter extends BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> {

    private int imageSize;
    private FullClickListener mFullClickListener;

    public UAdapter(List<UserPreviewsBean> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                2 * mContext.getResources().getDimensionPixelSize(R.dimen.eight_dp)) / 3;
        handleClick();
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
        bindView.baseBind.userName.setText(target.getUser().getName());
        bindView.baseBind.userShowOne.setImageResource(android.R.color.transparent);
        bindView.baseBind.userShowTwo.setImageResource(android.R.color.transparent);
        bindView.baseBind.userShowThree.setImageResource(android.R.color.transparent);

        final List<ImageView> views = Arrays.asList(bindView.baseBind.userShowOne, bindView.baseBind.userShowTwo, bindView.baseBind.userShowThree);
        final List<Serializable> shows = new ArrayList<>(target.getIllusts().subList(0, Math.min(3, target.getIllusts().size())));
        if (shows.size() < 3) {
            shows.addAll(target.getNovels().subList(0, Math.min(3 - shows.size(), target.getNovels().size())));
        }
        for (int i = 0; i < 3; i++) {
            Serializable item = i < shows.size() ? shows.get(i) : null;
            Object model = null;
            if (item instanceof IllustsBean) {
                model = GlideUtil.getMediumImg((IllustsBean) shows.get(i));
            } else if (item instanceof NovelBean) {
                model = GlideUtil.getUrl(((NovelBean) shows.get(i)).getImage_urls().getMedium());
            }
            Glide.with(mContext).load(model)
                    .placeholder(R.color.light_bg)
                    .into(views.get(i));
        }

        Glide.with(mContext).load(GlideUtil.getUrl(allIllust.get(position)
                .getUser().getProfile_image_urls().getMedium())).error(R.drawable.no_profile).into(bindView.baseBind.userHead);
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

    private void handleClick() {
        setFullClickListener(new FullClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 0) { //普通item
                    Common.showUser(mContext, allIllust.get(position));
                } else if (viewType == 1) { //关注按钮
                    if (allIllust.get(position).getUser().isIs_followed()) {
                        PixivOperate.postUnFollowUser(allIllust.get(position).getUser().getId());
                        Button postFollow = ((Button) v);
                        allIllust.get(position).getUser().setIs_followed(false);
                        postFollow.setText(mContext.getString(R.string.post_follow));
                    } else {
                        PixivOperate.postFollowUser(allIllust.get(position).getUser().getId(), Params.TYPE_PUBLUC);
                        allIllust.get(position).getUser().setIs_followed(true);
                        Button postFollow = ((Button) v);
                        postFollow.setText(mContext.getString(R.string.post_unfollow));
                    }
                }
            }

            @Override
            public void onItemLongClick(View v, int position, int viewType) {
                if (!allIllust.get(position).getUser().isIs_followed()) {
                    PixivOperate.postFollowUser(allIllust.get(position).getUser().getId(), Params.TYPE_PRIVATE);
                    Button postFollow = ((Button) v);
                    postFollow.setText(mContext.getString(R.string.post_unfollow));
                }
            }
        });
    }

    @Override
    public void setLiked(int id, boolean isLike) {
        if (id == 0) {
            return;
        }

        if (allIllust == null || allIllust.size() == 0) {
            return;
        }

        for (int i = 0; i < allIllust.size(); i++) {
            if (allIllust.get(i).getUser().getId() == id) {
                //设置这个用户为已关注状态
                allIllust.get(i).getUser().setIs_followed(isLike);
                if (headerSize() != 0) {//如果有header
                    notifyItemChanged(i + headerSize());
                } else { //没有header
                    notifyItemChanged(i);
                }
                break;
            }
        }
    }
}
