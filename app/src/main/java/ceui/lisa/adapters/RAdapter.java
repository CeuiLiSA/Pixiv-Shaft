package ceui.lisa.adapters;

import android.content.Context;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyRankIllustHorizontalBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;

public class RAdapter extends BaseAdapter<IllustsBean, RecyRankIllustHorizontalBinding> {

    public RAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_rank_illust_horizontal;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyRankIllustHorizontalBinding> bindView, int position) {
        bindView.baseBind.title.setText(allIllust.get(position).getTitle());
        bindView.baseBind.author.setText(allIllust.get(position).getUser().getName());
        Glide.with(mContext).load(GlideUtil.getUrl(allIllust.get(position)
                .getImage_urls().getMedium()))
                .placeholder(R.color.light_bg).into(bindView.baseBind.illustImage);
        Glide.with(mContext).load(GlideUtil.getUrl(allIllust.get(position)
                .getUser().getProfile_image_urls().getMedium()))
                .placeholder(R.color.light_bg).into(bindView.baseBind.userHead);
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
