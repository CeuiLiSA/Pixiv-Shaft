package ceui.lisa.fragments;

import android.view.View;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;

import ceui.lisa.R;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.GlideUtil;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentImageDetail extends BaseFragment {

    private IllustsBean mIllustsBean;
    private int index;
    private PhotoView mImageView;

    public static FragmentImageDetail newInstance(IllustsBean illustsBean, int index) {
        FragmentImageDetail fragmentImageDetail = new FragmentImageDetail();
        fragmentImageDetail.mIllustsBean = illustsBean;
        fragmentImageDetail.index = index;
        return fragmentImageDetail;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_image_detail;
    }

    @Override
    View initView(View v) {
        mImageView = v.findViewById(R.id.illust_image);
        Glide.with(mContext)
                //.load(GlideUtil.getOriginal(mIllustsBean, index))
                .load(GlideUtil.getLargeImage(mIllustsBean, index))
                .transition(withCrossFade())
                .into(mImageView);
        return v;
    }

    @Override
    void initData() {

    }
}
