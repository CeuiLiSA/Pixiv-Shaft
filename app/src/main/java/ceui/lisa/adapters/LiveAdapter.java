package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyItemLiveBinding;
import ceui.lisa.models.Live;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;

public class LiveAdapter extends BaseAdapter<Live, RecyItemLiveBinding> {

    private int imageSize;

    public LiveAdapter(@Nullable List<Live> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels - DensityUtil.dp2px(36.0f)) / 2;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_item_live;
    }

    @Override
    public void bindData(Live target, ViewHolder<RecyItemLiveBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.image.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        bindView.baseBind.image.setLayoutParams(params);
        if (!TextUtils.isEmpty(target.getThumbnail_image_url())) {
            Glide.with(mContext).load(GlideUtil.getUrl(target.getThumbnail_image_url())).into(bindView.baseBind.image);
        }
        bindView.baseBind.image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showToast("开发中");
            }
        });
    }
}
