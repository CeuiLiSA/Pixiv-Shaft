package ceui.lisa.adapters;

import android.content.Context;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyRankHorizontalBinding;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.GlideUtil;

public class RAdapter extends BaseAdapter<IllustsBean, RecyRankHorizontalBinding> {

    public RAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_rank_horizontal;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyRankHorizontalBinding> bindView, int position) {
        bindView.baseBind.title.setText(allIllust.get(position).getTitle());
        bindView.baseBind.author.setText(allIllust.get(position).getUser().getName());
        Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                .getImage_urls().getMedium()))
                .placeholder(R.color.light_bg).into(bindView.baseBind.illustImage);
        Glide.with(mContext).load(GlideUtil.getMediumImg(allIllust.get(position)
                .getUser().getProfile_image_urls().getMedium()))
                .placeholder(R.color.light_bg).into(bindView.baseBind.userHead);
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
