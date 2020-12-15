package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import ceui.lisa.R;
import ceui.lisa.core.GlideApp;
import ceui.lisa.core.GlideConfiguration;
import ceui.lisa.databinding.FragmentTestBinding;
import ceui.lisa.utils.Common;
import me.jessyan.progressmanager.ProgressListener;
import me.jessyan.progressmanager.ProgressManager;
import me.jessyan.progressmanager.body.ProgressInfo;
import okhttp3.OkHttpClient;

public class TestFragment extends BaseFragment<FragmentTestBinding>{


    public static TestFragment newInstance(int index) {
        Bundle args = new Bundle();
        args.putInt("index", index);
        TestFragment fragment = new TestFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected void initView() {
        ProgressManager.getInstance().addResponseListener("https://pixiv.cat/76749683.jpg", new ProgressListener() {
            @Override
            public void onProgress(ProgressInfo progressInfo) {
                if (progressInfo.isFinish()) {
                    baseBind.donutProgress.setVisibility(View.INVISIBLE);
                } else {
                    baseBind.donutProgress.setProgress(progressInfo.getPercent());
                }
            }

            @Override
            public void onError(long id, Exception e) {

            }
        });

        GlideApp.with(mContext)
                .load("https://pixiv.cat/76749683.jpg")
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(baseBind.imageView);
    }

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_test;
    }
}
