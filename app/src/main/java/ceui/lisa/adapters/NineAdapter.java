package ceui.lisa.adapters;

import android.content.Context;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyNineBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;

public class NineAdapter extends BaseAdapter<GlideUrl, RecyNineBinding> {

    private final IllustsBean illust;

    public NineAdapter(@Nullable List<GlideUrl> targetList,
                       Context context, IllustsBean illust) {
        super(targetList, context);
        this.illust = illust;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_nine;
    }

    @Override
    public void bindData(GlideUrl target, ViewHolder<RecyNineBinding> bindView, int position) {

        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
        if (illust.getPage_count() == 1) {
            int imageSize = mContext.getResources().getDisplayMetrics().widthPixels - DensityUtil.dp2px(32.0f);
            params.width = imageSize;
            params.height = imageSize * illust.getHeight() / illust.getWidth();
        } else if (illust.getPage_count() == 2 || illust.getPage_count() == 4) {
            int imageSize = (mContext.getResources().getDisplayMetrics().widthPixels - DensityUtil.dp2px(20.0f)) / 2;
            params.width = imageSize;
            params.height = imageSize;
        } else {
            int imageSize = (mContext.getResources().getDisplayMetrics().widthPixels - DensityUtil.dp2px(32.0f)) / 3;
            params.width = imageSize;
            params.height = imageSize;
        }
        bindView.baseBind.illustImage.setLayoutParams(params);
        bindView.baseBind.illustImage.setNestedScrollingEnabled(true);
        Glide.with(mContext).load(target)
                .placeholder(R.color.light_bg)
                .into(bindView.baseBind.illustImage);
    }
}
