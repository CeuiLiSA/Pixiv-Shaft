package ceui.lisa.adapters;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyRankIllustHorizontalBinding;
import ceui.pixiv.api.model.Illust;
import ceui.lisa.utils.GlideUtil;

public class RAdapter extends BaseAdapter<Illust, RecyRankIllustHorizontalBinding> {

    public RAdapter(List<Illust> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_rank_illust_horizontal;
    }

    @Override
    public void bindData(Illust target, ViewHolder<RecyRankIllustHorizontalBinding> bindView, int position) {
        Illust bean = allItems.get(position);
        bindView.baseBind.title.setText(bean.getTitle());
        // user / image_urls / profile_image_urls 都可能为 null(精简·网页来源 bean,见 #569;发现页
        // 站长推荐/当前最热的 shaft-api-v2 上报 bean 也可能缺字段)。链式 getter 直接点会 NPE 崩,
        // 改走判空:author 前判 user;图片走 GlideUtil.getLargeImage / getHead —— 内部对 image_urls /
        // profile_image_urls 为 null 时返 null 交 Glide 兜底,不崩。对完整 bean 行为不变。
        bindView.baseBind.author.setText(bean.getUser() != null ? bean.getUser().getName() : "");
        // 排行榜 hero 卡用 large(600x1200_90)比 medium(540_70)在 180dp 卡里更清晰。
        Glide.with(mContext).load(GlideUtil.getLargeImage(bean))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(bindView.baseBind.illustImage);
        Glide.with(mContext).load(GlideUtil.getHead(bean.getUser()))
                .placeholder(R.color.light_bg).error(R.drawable.no_profile).into(bindView.baseBind.userHead);
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
        // 长按(对齐 IAdapter):不设监听的调用方(发现页货架)行为不变,itemView 连 longClickable
        // 都不会被打开。返回 true 吃掉这一下,否则横向条会把它当成点击继续往下传。
        if (mOnItemLongClickListener != null) {
            bindView.itemView.setOnLongClickListener(v -> {
                mOnItemLongClickListener.onItemLongClick(v, position, 0);
                return true;
            });
        }
    }
}
