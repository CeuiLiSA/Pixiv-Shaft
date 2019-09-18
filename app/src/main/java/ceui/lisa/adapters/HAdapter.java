package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyTagGridBinding;
import ceui.lisa.databinding.RecyUserEventBinding;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.TrendingtagResponse;
import ceui.lisa.utils.GlideUtil;

public class HAdapter extends BaseAdapter<TrendingtagResponse.TrendTagsBean, RecyTagGridBinding> implements MultiDownload {

    private int imageSize = 0;

    public HAdapter(List<TrendingtagResponse.TrendTagsBean> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.two_dp))/3;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_tag_grid;
    }

    @Override
    public void bindData(TrendingtagResponse.TrendTagsBean target, ViewHolder<RecyTagGridBinding> bindView, int position) {
        if(position == 0){
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.height = imageSize * 2;
            params.width = mContext.getResources().getDisplayMetrics().widthPixels;
            bindView.baseBind.illustImage.setLayoutParams(params);
            Glide.with(mContext)
                    .load(GlideUtil.getLargeImage(allIllust.get(position).getIllust()))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
        }else {
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.height = imageSize;
            params.width = imageSize;
            bindView.baseBind.illustImage.setLayoutParams(params);
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(allIllust.get(position).getIllust()))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
        }
        bindView.baseBind.title.setText(!TextUtils.isEmpty(allIllust.get(position).getTranslated_name()) ?
                allIllust.get(position).getTranslated_name() :
                allIllust.get(position).getTag());

        bindView.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startDownload();
                return true;
            }
        });
        if(mOnItemClickListener != null){
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<IllustsBean> getIllustList() {
        List<IllustsBean> tempList = new ArrayList<>();
        for (int i = 0; i < allIllust.size(); i++) {
            tempList.add(allIllust.get(i).getIllust());
        }
        return tempList;
    }
}
