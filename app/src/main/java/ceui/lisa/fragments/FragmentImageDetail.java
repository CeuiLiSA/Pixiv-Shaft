package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.Glide;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentImageDetailBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentImageDetail extends BaseBindFragment<FragmentImageDetailBinding> {

    private IllustsBean mIllustsBean;
    private int index;
    private String url;

    public static FragmentImageDetail newInstance(IllustsBean illustsBean, int index) {
        FragmentImageDetail fragmentImageDetail = new FragmentImageDetail();
        fragmentImageDetail.mIllustsBean = illustsBean;
        fragmentImageDetail.index = index;
        return fragmentImageDetail;
    }

    public static FragmentImageDetail newInstance(String pUrl) {
        Bundle args = new Bundle();
        args.putString(Params.URL, pUrl);
        FragmentImageDetail fragment = new FragmentImageDetail();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        url = bundle.getString(Params.URL);
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_image_detail;
    }

    @Override
    void initData() {
        BarUtils.setNavBarVisibility(mActivity, false);
        if (!TextUtils.isEmpty(url)) {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(url))
                    .transition(withCrossFade())
                    .into(baseBind.illustImage);
        } else {
            if (Shaft.sSettings.isFirstImageSize()) {
                Glide.with(mContext)
                        .load(GlideUtil.getOriginal(mIllustsBean, index))
                        .transition(withCrossFade())
                        .into(baseBind.illustImage);
            } else {
                Glide.with(mContext)
                        .load(GlideUtil.getLargeImage(mIllustsBean, index))
                        .transition(withCrossFade())
                        .into(baseBind.illustImage);
            }
        }
    }
}
