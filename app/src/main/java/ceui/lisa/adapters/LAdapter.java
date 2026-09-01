 package ceui.lisa.adapters;

import android.content.Context;
import android.view.ViewGroup;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyCardIllustBinding;
import ceui.pixiv.api.model.Illust;
import ceui.lisa.utils.GlideUtil;

public class LAdapter extends BaseAdapter<Illust, RecyCardIllustBinding> {

    private final int imageSize;

    public LAdapter(List<Illust> targetList, Context context) {
        super(targetList, context);
        // 作者其他作品横向条:让 3 张卡在「区块 12dp 内边距 + 卡间 8dp 间隔」内正好排满,首卡与
        // 标题左对齐、末卡不被屏幕边裁成细条(见反馈)。48dp = 两侧 12dp 容器内边距 + 卡间 8dp×3。
        android.util.DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        imageSize = (dm.widthPixels - (int) (48 * dm.density)) / 3;
    }

    public int getImageSize() {
        return imageSize;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_card_illust;
    }

    @Override
    public void bindData(Illust target, ViewHolder<RecyCardIllustBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.imageView.getLayoutParams();
        params.width = imageSize;
        params.height = imageSize;
        bindView.baseBind.imageView.setLayoutParams(params);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(target))
                .placeholder(R.color.second_light_bg)
                .into(bindView.baseBind.imageView);
        bindView.itemView.setOnClickListener(view -> {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(view, position, 0);
            }
        });
    }
}
