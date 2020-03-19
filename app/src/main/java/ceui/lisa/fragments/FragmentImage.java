package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.bumptech.glide.Glide;

import java.util.Collections;

import ceui.lisa.R;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.databinding.FragmentImageBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentImage extends BaseBindFragment<FragmentImageBinding> {

    private IllustsBean mIllustsBean;

    public static FragmentImage newInstance(IllustsBean i) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, i);
        FragmentImage fragment = new FragmentImage();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mIllustsBean = ((IllustsBean) bundle.getSerializable(Params.CONTENT));
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_image;
    }

    @Override
    public void initView(View view) {
        Glide.with(mContext)
                .load(GlideUtil.getLargeImage(mIllustsBean))
                .transition(withCrossFade())
                .into(baseBind.illustImage);
        baseBind.illustImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View pView) {
                DataChannel.get().setIllustList(Collections.singletonList(mIllustsBean));
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", 0);
                startActivity(intent);
            }
        });
    }
}
