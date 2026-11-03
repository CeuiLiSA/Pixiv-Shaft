package ceui.lisa.adapters;

import android.content.Context;

import android.view.View;
import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyDiscoverTagBinding;
import ceui.lisa.utils.GlideUtil;
import ceui.loxia.Tag;
import ceui.pixiv.ui.prime.PrimeTagIndexItem;

/**
 * 发现页「热度标签」横向货架 adapter。数据是本地策展 asset({@link PrimeTagIndexItem}),
 * 封面取 preview_square_urls[0]。点击回调交给 Fragment 打开 PrimeTagDetail(见 FragmentCenter)。
 * 结构对齐 {@link RAdapter}(排行榜横向货架),复用 scrim-title 视觉。
 */
public class DiscoverTagAdapter extends BaseAdapter<PrimeTagIndexItem, RecyDiscoverTagBinding> {

    public DiscoverTagAdapter(List<PrimeTagIndexItem> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_discover_tag;
    }

    @NonNull
    @Override
    protected RecyDiscoverTagBinding bindView(@NonNull View root) {
        return RecyDiscoverTagBinding.bind(root);
    }

    @Override
    public void bindData(PrimeTagIndexItem target, ViewHolder<RecyDiscoverTagBinding> bindView, int position) {
        Tag tag = target.getTag();
        // 展示中文译名优先,回退原名(和 PrimeTagsFragment 传给详情页的 name 一致)。
        String name = "";
        if (tag != null) {
            name = tag.getTranslated_name() != null ? tag.getTranslated_name() : tag.getName();
        }
        bindView.baseBind.tagName.setText(name);

        String cover = null;
        List<String> urls = target.getPreviewSquareUrls();
        if (urls != null && !urls.isEmpty()) {
            cover = urls.get(0);
        }
        // pximg 需要 referer,GlideUtil.getUrl 包成带头的 GlideUrlChild;null 交给 Glide 兜底占位。
        // crossFade:图片渐显而非突现,配合货架级淡入,骨架→内容整体柔和。
        Glide.with(mContext).load(GlideUtil.getUrl(cover))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(bindView.baseBind.tagImage);

        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
