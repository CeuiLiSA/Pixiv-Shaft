package ceui.lisa.fragments;

import android.media.Image;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import jp.wasabeef.glide.transformations.BlurTransformation;

import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

public class FragmentSingleIllust extends BaseFragment {

    private IllustsBean illust;

    public static FragmentSingleIllust newInstance(IllustsBean illustsBean){
        FragmentSingleIllust fragmentSingleIllust = new FragmentSingleIllust();
        fragmentSingleIllust.setIllust(illustsBean);
        return fragmentSingleIllust;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_single_illust;
    }

    @Override
    View initView(View v) {
        ImageView imageView = v.findViewById(R.id.bg_image);
        ImageView originImage = v.findViewById(R.id.origin_image);


        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        ViewGroup.LayoutParams params = originImage.getLayoutParams();
        int width = mContext.getResources().getDisplayMetrics().widthPixels - 2 * DensityUtil.dp2px(16.0f);
        int height = illust.getHeight() * width / illust.getWidth();
        params.height = height;
        originImage.setLayoutParams(params);
        Glide.with(mContext).load(GlideUtil.getSquare(illust)).apply(
                bitmapTransform(new BlurTransformation(25, 3)))
                .into(imageView);
        Glide.with(mContext).load(GlideUtil.getLargeImage(illust)).into(originImage);
        //((ViewPagerActivity)getActivity()).getToolbar().setTitle(illust.getTitle());
        return v;
    }

    @Override
    void initData() {

    }

    public IllustsBean getIllust() {
        return illust;
    }

    public void setIllust(IllustsBean illust) {
        this.illust = illust;
    }
}
