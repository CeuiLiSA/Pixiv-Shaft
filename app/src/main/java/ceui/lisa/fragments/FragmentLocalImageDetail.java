package ceui.lisa.fragments;

import android.view.View;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;

import ceui.lisa.R;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentLocalImageDetail extends BaseFragment {

    private String filePath;
    private PhotoView mImageView;

    public static FragmentLocalImageDetail newInstance(String filePath){
        FragmentLocalImageDetail fragmentImageDetail = new FragmentLocalImageDetail();
        fragmentImageDetail.filePath = filePath;
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
                .load(filePath)
                .transition(withCrossFade())
                .into(mImageView);
        return v;
    }

    @Override
    void initData() {

    }
}
