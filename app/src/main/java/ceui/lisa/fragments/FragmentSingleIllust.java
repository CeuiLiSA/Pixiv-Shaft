package ceui.lisa.fragments;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.CardView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;
import com.github.ybq.android.spinkit.style.CubeGrid;
import com.google.gson.Gson;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import de.hdodenhof.circleimageview.CircleImageView;
import jp.wasabeef.glide.transformations.BlurTransformation;
import me.next.tagview.TagCloudView;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

/**
 * 插画详情
 */
public class FragmentSingleIllust extends BaseFragment {

    private IllustsBean illust;
    private ProgressBar mProgressBar;
    private ImageView refresh, imageView, originImage;
    private Bundle mBundle;
    private boolean isBig = false;
    private Spring spring;

    public static FragmentSingleIllust newInstance(IllustsBean illustsBean, Bundle bundle) {
        FragmentSingleIllust fragmentSingleIllust = new FragmentSingleIllust();
        fragmentSingleIllust.setIllust(illustsBean);
        fragmentSingleIllust.mBundle = bundle;
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
        /**
         * 计算原图 宽高
         */
        ViewGroup.LayoutParams params = originImage.getLayoutParams();
        int width = mContext.getResources().getDisplayMetrics().widthPixels - 2 * DensityUtil.dp2px(12.0f);
        params.height = illust.getHeight() * width / illust.getWidth();
        originImage.setLayoutParams(params);
        originImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showToast(illust.getTitle());
            }
        });


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
        toolbar.setNavigationOnClickListener(view -> getActivity().finish());

        CardView viewRelated = v.findViewById(R.id.related_illust);
        viewRelated.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "相关作品");
                intent.putExtra(TemplateFragmentActivity.EXTRA_ILLUST_ID, illust.getId());
                intent.putExtra(TemplateFragmentActivity.EXTRA_ILLUST_TITLE, illust.getTitle());
                startActivity(intent);
            }
        });

        /**
         * 设置一个空白的imageview作为头部，作为占位,
         * 这样原图就会刚好在toolbar 下方，不会被toolbar遮住
         */
        ImageView head = v.findViewById(R.id.head);
        ViewGroup.LayoutParams headParams = head.getLayoutParams();
        headParams.height = Shaft.statusHeight + Shaft.toolbarHeight;
        head.setLayoutParams(headParams);


        TextView userName = v.findViewById(R.id.user_name);
        TextView follow = v.findViewById(R.id.follow);
        CircleImageView userHead = v.findViewById(R.id.user_head);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(illust.getUser().getProfile_image_urls().getMedium()))
                .into(userHead);
        userName.setText(illust.getUser().getName());
        TagCloudView tagCloudView = v.findViewById(R.id.illust_tag);
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < illust.getTags().size(); i++) {
            tags.add(illust.getTags().get(i).getName());
        }
        tagCloudView.setTags(tags);
        TextView date = v.findViewById(R.id.illust_date);
        TextView totalView = v.findViewById(R.id.illust_view);
        TextView like = v.findViewById(R.id.illust_like);
        date.setText(illust.getCreate_date().substring(0, 16));
        totalView.setText(String.valueOf(illust.getTotal_view()));
        like.setText(String.valueOf(illust.getTotal_bookmarks()));
        return v;
    }

    private void loadImage() {
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

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser) {
            insertViewHistory();
        }
    }

    public void setIllust(IllustsBean illust) {
        this.illust = illust;
    }

    private void insertViewHistory() {
        IllustHistoryEntity illustHistoryEntity = new IllustHistoryEntity();
        illustHistoryEntity.setIllustID(illust.getId());
        Gson gson = new Gson();
        illustHistoryEntity.setIllustJson(gson.toJson(illust));
        illustHistoryEntity.setTime(System.currentTimeMillis());
        AppDatabase.getAppDatabase(Shaft.getContext()).trackDao().insert(illustHistoryEntity);
    }
}
