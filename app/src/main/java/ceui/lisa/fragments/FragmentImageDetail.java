package ceui.lisa.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.chrisbanes.photoview.OnOutsidePhotoTapListener;
import com.github.chrisbanes.photoview.OnPhotoTapListener;

import java.io.File;
import java.util.concurrent.ExecutionException;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.core.GlideApp;
import ceui.lisa.databinding.FragmentImageDetailBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import me.jessyan.progressmanager.ProgressListener;
import me.jessyan.progressmanager.ProgressManager;
import me.jessyan.progressmanager.body.ProgressInfo;

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
    protected void initData() {
        baseBind.illustImage.setTransitionName("big_image_" + index);
        BarUtils.setNavBarVisibility(mActivity, false);
        loadImage();
    }

    private void loadImage() {
        final String imageUrl = IllustDownload.getUrl(mIllustsBean, index);
        ProgressManager.getInstance().addResponseListener(imageUrl, new ProgressListener() {
            @Override
            public void onProgress(ProgressInfo progressInfo) {
                baseBind.donutProgress.setProgress(progressInfo.getPercent());
            }

            @Override
            public void onError(long id, Exception e) {

            }
        });
        GlideApp.with(mContext)
                .asFile()
                .load(imageUrl)
                .listener(new RequestListener<File>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<File> target, boolean isFirstResource) {
                        baseBind.donutProgress.setVisibility(View.INVISIBLE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(File resource, Object model, Target<File> target, DataSource dataSource, boolean isFirstResource) {
                        baseBind.donutProgress.setVisibility(View.INVISIBLE);
                        baseBind.realIllustImage.setImageURI(Uri.fromFile(resource));
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
