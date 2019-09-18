package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.databinding.RecyUserEventBinding;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.GlideUtil;

public class IAdapter extends BaseAdapter<IllustsBean, RecyIllustStaggerBinding> {

    private int imageSize = 0;

    public IAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels) / 2;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_illust_stagger;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyIllustStaggerBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();

        params.width = imageSize;
        params.height = allIllust.get(position).getHeight() * imageSize / allIllust.get(position).getWidth();

        if (params.height < 350) {
            params.height = 350;
        } else if (params.height > 600) {
            params.height = 600;
        }

        bindView.baseBind.illustImage.setLayoutParams(params);
        bindView.baseBind.title.setText(allIllust.get(position).getTitle());
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(allIllust.get(position)))
                .placeholder(R.color.second_light_bg)
                .into(bindView.baseBind.illustImage);

        if (allIllust.get(position).getPage_count() == 1) {
            bindView.baseBind.pSize.setVisibility(View.GONE);
        } else {
            bindView.baseBind.pSize.setVisibility(View.VISIBLE);
            bindView.baseBind.pSize.setText(allIllust.get(position).getPage_count() + "P");
        }
    }
}
