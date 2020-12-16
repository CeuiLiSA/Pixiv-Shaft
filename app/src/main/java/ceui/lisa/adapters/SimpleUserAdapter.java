package ceui.lisa.adapters;

import android.content.Context;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecySimpleUserBinding;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.interfaces.FullClickListener;
import ceui.lisa.models.UserBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class SimpleUserAdapter extends BaseAdapter<UserBean, RecySimpleUserBinding> {

    private FullClickListener mFullClickListener;

    public SimpleUserAdapter(@Nullable List<UserBean> targetList, Context context) {
        super(targetList, context);
        handleClick();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_simple_user;
    }

    @Override
    public void bindData(UserBean target, ViewHolder<RecySimpleUserBinding> bindView, int position) {
        bindView.baseBind.userName.setText(target.getName());
        Glide.with(mContext).load(GlideUtil.getUrl(allIllust.get(position)
                .getProfile_image_urls().getMedium())).into(bindView.baseBind.userHead);
        bindView.baseBind.postLikeUser.setText(allIllust.get(position).isIs_followed() ?
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

    public SimpleUserAdapter setFullClickListener(FullClickListener fullClickListener) {
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
                    if (allIllust.get(position).isIs_followed()) {
                        PixivOperate.postUnFollowUser(allIllust.get(position).getId());
                        Button postFollow = ((Button) v);
                        allIllust.get(position).setIs_followed(false);
                        postFollow.setText(mContext.getString(R.string.post_follow));
                    } else {
                        PixivOperate.postFollowUser(allIllust.get(position).getId(),
                                Params.TYPE_PUBLUC);
                        allIllust.get(position).setIs_followed(true);
                        Button postFollow = ((Button) v);
                        postFollow.setText(mContext.getString(R.string.post_unfollow));
                    }
                }
            }

            @Override
            public void onItemLongClick(View v, int position, int viewType) {
                if (!allIllust.get(position).isIs_followed()) {
                    PixivOperate.postFollowUser(allIllust.get(position).getId(),
                            Params.TYPE_PRIVATE);
                    Button postFollow = ((Button) v);
                    postFollow.setText(mContext.getString(R.string.post_unfollow));
                }
            }
        });
    }
}
