package ceui.lisa.fragments;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.core.GlideApp;
import ceui.lisa.databinding.FragmentImageDetailBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUrlChild;
import ceui.lisa.utils.Params;
import me.jessyan.progressmanager.ProgressListener;
import me.jessyan.progressmanager.ProgressManager;
import me.jessyan.progressmanager.body.ProgressInfo;

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
    protected void initView() {
        baseBind.emptyActionButton.setOnClickListener(v -> loadImage());
        baseBind.bigImage.setDoubleTapZoomDuration(250);
        //插画二级详情保持屏幕常亮
        if (Shaft.sSettings.isIllustDetailKeepScreenOn()) {
            baseBind.getRoot().setKeepScreenOn(true);
        }
    }

    @Override
    protected void initData() {
        loadImage();
    }

    private void loadImage() {
        baseBind.emptyFrame.setVisibility(View.GONE);
        baseBind.progressLayout.getRoot().setVisibility(View.VISIBLE);
        String imageUrl;
        if (mIllustsBean == null && !TextUtils.isEmpty(url)) {
            setUpMediumResolutionDoubleTap();
            imageUrl = url;
        } else {
            final String originUrl = IllustDownload.getUrl(mIllustsBean, index);
            if (Shaft.getMMKV().decodeBool(originUrl)) {
                setUpHighResolutionDoubleTap();
                imageUrl = originUrl;
            } else {
                if (!TextUtils.isEmpty(url)) {
                    setUpMediumResolutionDoubleTap();
                    imageUrl = url;
                } else {
                    if (Shaft.sSettings.isShowOriginalImage()) {
                        setUpHighResolutionDoubleTap();
                        imageUrl = IllustDownload.getUrl(mIllustsBean, index, Params.IMAGE_RESOLUTION_ORIGINAL);
                    } else {
                        setUpMediumResolutionDoubleTap();
                        imageUrl = IllustDownload.getUrl(mIllustsBean, index, Params.IMAGE_RESOLUTION_LARGE);
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
                        baseBind.progressLayout.getRoot().setVisibility(View.GONE);
                        baseBind.emptyFrame.setVisibility(View.VISIBLE);
                        if (e != null) {
                            baseBind.emptyTitle.setText(e.getMessage());
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(File resource, Object model, Target<File> target, DataSource dataSource, boolean isFirstResource) {
                        baseBind.progressLayout.getRoot().setVisibility(View.GONE);
                        baseBind.emptyFrame.setVisibility(View.GONE);
                        Common.showLog("onResourceReady " + resource.getPath());
                        return false;
                    }
                })
                .into(new CustomViewTarget<SubsamplingScaleImageView, File>(baseBind.bigImage) {
                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    }

                    @Override
                    public void onResourceReady(@NonNull File resource, @Nullable Transition<? super File> transition) {
                        baseBind.bigImage.setImage(ImageSource.uri(Uri.fromFile(resource)));
                    }

                    @Override
                    protected void onResourceCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    private void setUpHighResolutionDoubleTap() {
        baseBind.bigImage.setMaxScale(3.8F);
        baseBind.bigImage.setDoubleTapZoomScale(1.8F);
    }

    private void setUpMediumResolutionDoubleTap() {
        baseBind.bigImage.setMaxScale(7F);
        baseBind.bigImage.setDoubleTapZoomScale(4F);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("mIllustsBean", mIllustsBean);
        outState.putInt("index", index);
    }
}
