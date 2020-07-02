package ceui.lisa.fragments;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
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
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentImageDetail extends BaseFragment<FragmentImageDetailBinding> {

    private IllustsBean mIllustsBean;
    private int index;
    private String url;

    public static FragmentImageDetail newInstance(IllustsBean illustsBean, int index) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, illustsBean);
        args.putInt(Params.INDEX, index);
        FragmentImageDetail fragment = new FragmentImageDetail();
        fragment.setArguments(args);
        return fragment;
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
        mIllustsBean = (IllustsBean) bundle.getSerializable(Params.CONTENT);
        index = bundle.getInt(Params.INDEX);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_image_detail;
    }

    @Override
    void initData() {
        baseBind.illustImage.setTransitionName("big_image_" + index);
        BarUtils.setNavBarVisibility(mActivity, false);
        if (!TextUtils.isEmpty(url)) {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(url))
                    .transition(withCrossFade())
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                            baseBind.progress.setVisibility(View.INVISIBLE);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                            mActivity.startPostponedEnterTransition();
                            baseBind.progress.setVisibility(View.INVISIBLE);
                            return false;
                        }
                    })
                    .into(baseBind.illustImage);
        } else {
            baseBind.reload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadImage();
                }
            });
            loadImage();
        }
    }

    private void loadImage() {
        baseBind.reload.setVisibility(View.INVISIBLE);
        baseBind.progress.setVisibility(View.VISIBLE);
        RequestListener<Drawable> requestListener = new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                baseBind.progress.setVisibility(View.INVISIBLE);
                baseBind.reload.setVisibility(View.VISIBLE);
                Common.showToast("加载失败，点击重试");
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                baseBind.progress.setVisibility(View.INVISIBLE);
                if (Shaft.sSettings.isFirstImageSize()) {

                } else {
                    mActivity.startPostponedEnterTransition();
                }
                return false;
            }
        };
        if (Shaft.sSettings.isFirstImageSize()) {
            Common.showLog(className + "展示原图");
            Glide.with(mContext)
                    .load(GlideUtil.getOriginalWithInvertProxy(mIllustsBean, index))
                    .transition(withCrossFade())
                    .listener(requestListener)
                    .into(baseBind.illustImage);
        } else {
            Common.showLog(className + "展示大图");
            Glide.with(mContext)
                    .load(GlideUtil.getLargeImage(mIllustsBean, index))
                    .transition(withCrossFade())
                    .listener(requestListener)
                    .into(baseBind.illustImage);
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
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("mIllustsBean", mIllustsBean);
        outState.putInt("index", index);
    }
}
