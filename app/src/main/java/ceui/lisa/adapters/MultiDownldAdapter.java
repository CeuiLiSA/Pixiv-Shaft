package ceui.lisa.adapters;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyMultiDownloadBinding;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;

public class MultiDownldAdapter extends BaseAdapter<IllustsBean, RecyMultiDownloadBinding> implements MultiDownload {

    private int imageSize = 0;
    private Callback mCallback;

    public MultiDownldAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.two_dp)) / 3;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_multi_download;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyMultiDownloadBinding> bindView, int position) {

        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        bindView.baseBind.illustImage.setLayoutParams(params);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(allIllust.get(position)))
                .placeholder(R.color.light_bg)
                .into(bindView.baseBind.illustImage);

        bindView.baseBind.checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    allIllust.get(position).setChecked(true);
                } else {
                    allIllust.get(position).setChecked(false);
                }
                mCallback.doSomething(null);
            }
        });

        if (allIllust.get(position).isChecked()) {
            bindView.baseBind.checkbox.setChecked(true);
        } else {
            bindView.baseBind.checkbox.setChecked(false);
        }

        bindView.itemView.setOnClickListener(v ->
                mOnItemClickListener.onItemClick(bindView.itemView, position, 0));
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<IllustsBean> getIllustList() {
        return allIllust;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
        if (mCallback != null) {
            mCallback.doSomething(null);
        }
    }
}
