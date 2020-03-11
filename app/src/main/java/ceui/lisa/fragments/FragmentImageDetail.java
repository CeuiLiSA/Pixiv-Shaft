package ceui.lisa.fragments;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.chrisbanes.photoview.OnOutsidePhotoTapListener;
import com.github.chrisbanes.photoview.OnPhotoTapListener;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentImageDetailBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.ObjectTemp;
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
        if (mIllustsBean == null) {
            mIllustsBean = (IllustsBean) ObjectTemp.get("mIllustsBean");
        }
        baseBind.illustImage.setTransitionName("big_image_" + index);
        BarUtils.setNavBarVisibility(mActivity, false);
        if (!TextUtils.isEmpty(url)) {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(url))
                    .transition(withCrossFade())
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                            mActivity.startPostponedEnterTransition();
                            return false;
                        }
                    })
                    .into(baseBind.illustImage);
        } else {
            if (Shaft.sSettings.isFirstImageSize()) {
                Glide.with(mContext)
                        .load(GlideUtil.getOriginal(mIllustsBean, index))
                        .transition(withCrossFade())
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                                mActivity.startPostponedEnterTransition();
                                return false;
                            }
                        })
                        .into(baseBind.illustImage);
            } else {
                Glide.with(mContext)
                        .load(GlideUtil.getLargeImage(mIllustsBean, index))
                        .transition(withCrossFade())
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                                mActivity.startPostponedEnterTransition();
                                return false;
                            }
                        })
                        .into(baseBind.illustImage);
            }
        }
    }

    @Override
    public void initView(View view) {
        baseBind.illustImage.setOnPhotoTapListener(new OnPhotoTapListener() {
            @Override
            public void onPhotoTap(ImageView view, float x, float y) {
                mActivity.onBackPressed();
            }
        });
        baseBind.illustImage.setOnOutsidePhotoTapListener(new OnOutsidePhotoTapListener() {
            @Override
            public void onOutsidePhotoTap(ImageView imageView) {
                mActivity.onBackPressed();
            }
        });
    }

    @Override
    public void onDestroy() {
        ObjectTemp.put("mIllustsBean", mIllustsBean);
        super.onDestroy();
    }
}
