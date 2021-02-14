package ceui.lisa.fragments;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.core.GlideApp;
import ceui.lisa.databinding.FragmentImageDetailBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.feature.HostManager;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUrlChild;
import ceui.lisa.utils.Params;
import me.jessyan.progressmanager.ProgressListener;
import me.jessyan.progressmanager.ProgressManager;
import me.jessyan.progressmanager.body.ProgressInfo;
import xyz.zpayh.hdimage.HDImageView;
import xyz.zpayh.hdimage.OnBitmapLoadListener;

public class FragmentImageDetail extends BaseFragment<FragmentImageDetailBinding> {

    private IllustsBean mIllustsBean;
    private int index;
    private String url;
    private int imageWidth = 0;
    private int imageHeight = 0;

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
    protected void initView() {
        BarUtils.setNavBarVisibility(mActivity, false);

        baseBind.realIllustImage.setOnBitmapLoadListener(new OnBitmapLoadListener() {
            @Override
            public void onBitmapLoadReady() {

            }

            @Override
            public void onBitmapLoaded(int width, int height) {
                imageWidth = width;
                imageHeight = height;
            }

            @Override
            public void onBitmapLoadError(Exception e) {

            }
        });

        baseBind.realIllustImage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                adjustAutoScale();
            }
        });
    }

    @Override
    protected void initData() {
        loadImage();
    }

    private void loadImage() {
        final String imageUrl;
        final String originUrl = IllustDownload.getUrl(mIllustsBean, index);
        if (Shaft.getMMKV().decodeBool(originUrl)) {
            imageUrl = originUrl;
        } else {
            if (!TextUtils.isEmpty(url)) {
                imageUrl = url;
            } else {
                if (Shaft.sSettings.isShowOriginalImage()) {
                    imageUrl = IllustDownload.getUrl(mIllustsBean, index);
                } else {
                    if (mIllustsBean.getPage_count() == 1) {
                        imageUrl = HostManager.get().replaceUrl(mIllustsBean.getImage_urls().getLarge());
                    } else {
                        imageUrl = HostManager.get().replaceUrl(mIllustsBean.getMeta_pages().get(index).getImage_urls().getLarge());
                    }
                }
            }
        }
        ProgressManager.getInstance().addResponseListener(imageUrl, new ProgressListener() {
            @Override
            public void onProgress(ProgressInfo progressInfo) {
                baseBind.progressLayout.donutProgress.setProgress(progressInfo.getPercent());
            }

            @Override
            public void onError(long id, Exception e) {

            }
        });
        GlideApp.with(mContext)
                .asFile()
                .load(new GlideUrlChild(imageUrl))
                .listener(new RequestListener<File>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<File> target, boolean isFirstResource) {
                        baseBind.progressLayout.donutProgress.setVisibility(View.INVISIBLE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(File resource, Object model, Target<File> target, DataSource dataSource, boolean isFirstResource) {
                        baseBind.progressLayout.donutProgress.setVisibility(View.INVISIBLE);
                        Common.showLog("onResourceReady " + resource.getPath());
                        return false;
                    }
                })
                .into(new CustomViewTarget<HDImageView, File>(baseBind.realIllustImage) {
                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    }

                    @Override
                    public void onResourceReady(@NonNull File resource, @Nullable Transition<? super File> transition) {
                        view.setImageURI(Uri.fromFile(resource));
                    }

                    @Override
                    protected void onResourceCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("mIllustsBean", mIllustsBean);
        outState.putInt("index", index);
    }

    public void adjustAutoScale() {
        if (imageWidth <=0 || imageHeight <= 0){
            return;
        }

        int viewWidth = baseBind.realIllustImage.getWidth();
        int viewHeight = baseBind.realIllustImage.getHeight();

        float scale_w = (float) imageWidth / viewWidth;
        float scale_h = (float) imageHeight / viewHeight;

        float scale_init_inner = Math.min(viewWidth / (float) imageWidth, viewHeight / (float) imageHeight); // 内部处理后的初始Scale，minScale()
        float scale_init_side = Math.max(scale_w, scale_h); // 初始scale 此时吸附scale较大的一边
        float scale_max = scale_init_inner * scale_init_side; // 最大scale，显示原图级别
        float scale_other_side = scale_max / Math.min(scale_w, scale_h); // 目标scale，吸附另一边

        if (scale_w > 1.0f && scale_h > 1.0f) {
            baseBind.realIllustImage.setMaxScale(scale_max);
        } else {
            baseBind.realIllustImage.setMaxScale(scale_other_side);
        }
        baseBind.realIllustImage.setDoubleTapZoomScale(scale_other_side);
    }
}
