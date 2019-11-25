package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import java.util.Collections;

import ceui.lisa.R;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.DataChannel;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentImage extends BaseFragment {

    private IllustsBean mIllustsBean;

    public static FragmentImage newInstance(IllustsBean illustsBean) {
        FragmentImage fragmentImageDetail = new FragmentImage();
        fragmentImageDetail.mIllustsBean = illustsBean;
        return fragmentImageDetail;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_image;
    }

    @Override
    View initView(View v) {
        ImageView mImageView = v.findViewById(R.id.illust_image);
        Glide.with(mContext)
                .load(GlideUtil.getLargeImage(mIllustsBean))
                .transition(withCrossFade())
                .into(mImageView);
        mImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View pView) {
                DataChannel.get().setIllustList(Collections.singletonList(mIllustsBean));
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", 0);
                startActivity(intent);
            }
        });
        return v;
    }

    @Override
    void initData() {

    }
}
