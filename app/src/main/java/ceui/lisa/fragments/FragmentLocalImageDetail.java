package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import com.bumptech.glide.Glide;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentImageDetailBinding;
import ceui.lisa.databinding.FragmentImageDetailLocalBinding;
import ceui.lisa.utils.Params;
import xyz.zpayh.hdimage.OnBitmapLoadListener;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentLocalImageDetail extends BaseFragment<FragmentImageDetailLocalBinding> {

    private String filePath;

    public static FragmentLocalImageDetail newInstance(String filePath) {
        Bundle args = new Bundle();
        args.putString(Params.FILE_PATH, filePath);
        FragmentLocalImageDetail fragment = new FragmentLocalImageDetail();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        filePath = bundle.getString(Params.FILE_PATH);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_image_detail_local;
    }

    @Override
    public void initView() {
        baseBind.illustImage.setImageURI(filePath);
        baseBind.illustImage.setOnBitmapLoadListener(new OnBitmapLoadListener() {
            @Override
            public void onBitmapLoadReady() {

            }

            @Override
            public void onBitmapLoaded(int width, int height) {
                baseBind.progress.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onBitmapLoadError(Exception e) {
                baseBind.progress.setVisibility(View.INVISIBLE);
            }
        });
    }
}
