package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.RecyMultiDownloadBinding;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

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
        final IllustsBean illustsBean = allItems.get(position);
        Object tag = bindView.itemView.getTag(R.id.tag_image_url);
        if ((tag == null) || !(tag instanceof String) || !((String) tag).equals(illustsBean.getImage_urls().getMedium())) {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(illustsBean))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);

            bindView.itemView.setTag(R.id.tag_image_url, illustsBean.getImage_urls().getMedium());
        }

        bindView.baseBind.checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    illustsBean.setChecked(true);
                } else {
                    illustsBean.setChecked(false);
                }
                mCallback.doSomething(null);
            }
        });

        if (illustsBean.isChecked()) {
            bindView.baseBind.checkbox.setChecked(true);
        } else {
            bindView.baseBind.checkbox.setChecked(false);
        }

//        bindView.itemView.setOnClickListener(v -> bindView.baseBind.checkbox.performClick());
        bindView.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final PageData pageData = new PageData(allItems);
                Container.get().addPageToMap(pageData);

                Intent intent = new Intent(mContext, VActivity.class);
                intent.putExtra(Params.POSITION, position);
                intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                mContext.startActivity(intent);
            }
        });
        if (mOnItemLongClickListener != null) {
            bindView.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    mOnItemLongClickListener.onItemLongClick(view, position, 0);
                    return true;
                }
            });
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<IllustsBean> getIllustList() {
        return allItems;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
        if (mCallback != null) {
            mCallback.doSomething(null);
        }
    }
}
