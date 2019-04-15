package ceui.lisa.fragments;

import android.graphics.drawable.Drawable;
import android.media.Image;
import android.support.annotation.Nullable;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.ybq.android.spinkit.sprite.Sprite;
import com.github.ybq.android.spinkit.style.CubeGrid;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import jp.wasabeef.glide.transformations.BlurTransformation;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

public class FragmentSingleIllust extends BaseFragment {

    private IllustsBean illust;
    private ProgressBar mProgressBar;
    private ImageView refresh, imageView, originImage;

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
        imageView = v.findViewById(R.id.bg_image);
        originImage = v.findViewById(R.id.origin_image);
        mProgressBar = v.findViewById(R.id.progress);
        CubeGrid cubeGrid = new CubeGrid();
        cubeGrid.setColor(getResources().getColor(R.color.loginBackground));
        mProgressBar.setIndeterminateDrawable(cubeGrid);
        refresh = v.findViewById(R.id.refresh);
        refresh.setOnClickListener(view -> {
            refresh.setVisibility(View.INVISIBLE);
            loadImage();
        });
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        toolbar.setTitle(illust.getTitle() + "  ");
        toolbar.setTitleTextAppearance(mContext, R.style.toolbarText);
        ViewGroup.LayoutParams params = originImage.getLayoutParams();
        int width = mContext.getResources().getDisplayMetrics().widthPixels - 2 * DensityUtil.dp2px(12.0f);
        params.height = illust.getHeight() * width / illust.getWidth();
        originImage.setLayoutParams(params);
        return v;
    }

    private void loadImage(){
        mProgressBar.setVisibility(View.VISIBLE);
        Glide.with(mContext)
                .load(GlideUtil.getSquare(illust))
                .apply(bitmapTransform(new BlurTransformation(25, 3)))
                .transition(withCrossFade())
                .into(imageView);
        Glide.with(mContext)
                .load(GlideUtil.getLargeImage(illust))
                .transition(withCrossFade())
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        mProgressBar.setVisibility(View.INVISIBLE);
                        refresh.setVisibility(View.VISIBLE);
                        Common.showToast("图片加载失败");
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        mProgressBar.setVisibility(View.INVISIBLE);
                        refresh.setVisibility(View.INVISIBLE);
                        return false;
                    }
                })
                .into(originImage);
    }

    @Override
    void initData() {
        loadImage();
    }


    public void setIllust(IllustsBean illust) {
        this.illust = illust;
    }
}
