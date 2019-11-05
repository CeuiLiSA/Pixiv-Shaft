package ceui.lisa.fragments;

import android.view.View;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.GlideUtil;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentImage extends BaseFragment {

    private IllustsBean mIllustsBean;

    public static FragmentImage newInstance(IllustsBean illustsBean) {
        FragmentImage fragmentImageDetail = new FragmentImage();
        fragmentImageDetail.mIllustsBean = illustsBean;
        return fragmentImageDetail;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_image;
    }

    @Override
    View initView(View v) {
        PhotoView mImageView = v.findViewById(R.id.illust_image);
        Glide.with(mContext)
                .load(GlideUtil.getLargeImage(mIllustsBean))
                .transition(withCrossFade())
                .into(mImageView);
        return v;
    }

    @Override
    void initData() {

    }
}
