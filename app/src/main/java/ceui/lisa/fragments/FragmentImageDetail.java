package ceui.lisa.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

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
        BarUtils.setNavBarVisibility(mActivity, false);
        int imageWidth = mIllustsBean.getWidth();
        int imageHeight = mIllustsBean.getHeight();

        baseBind.realIllustImage.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int viewWidth = baseBind.realIllustImage.getWidth();
                int viewHeight = baseBind.realIllustImage.getHeight();

                float scale1 = (float) imageWidth / viewWidth;
                float scale2 = (float) imageHeight / viewHeight;

                if (!(scale1 < 1.0f) || !(scale2 < 1.0f)) {
                    float scale_src = Math.max(scale1, scale2); // 初始scale 此时吸附scale较大的一边
                    float scale_dst = scale_src*scale_src/Math.min(scale1, scale2); // 目标scale，吸附另一边

                    baseBind.realIllustImage.setMaxScale(scale_dst);
                    baseBind.realIllustImage.setDoubleTapZoomScale(scale_dst);
                }
                baseBind.realIllustImage.getViewTreeObserver().removeOnGlobalLayoutListener(this);
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
                        baseBind.realIllustImage.setImageURI(Uri.fromFile(resource));
                        Common.showLog("onResourceReady " + resource.getPath());
                        return false;
                    }
                })
                .submit();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("mIllustsBean", mIllustsBean);
        outState.putInt("index", index);
    }
}
