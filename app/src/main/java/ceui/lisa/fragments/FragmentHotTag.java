package ceui.lisa.fragments;

import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v7.widget.GridLayoutManager;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.HotTagAdapter;
import ceui.lisa.network.Retro;
import ceui.lisa.response.TrendingtagResponse;
import ceui.lisa.utils.AppBarStateChangeListener;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.GridItemDecoration;
import io.reactivex.Observable;
import jp.wasabeef.glide.transformations.BlurTransformation;

import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

public class FragmentHotTag extends BaseListFragment<TrendingtagResponse, HotTagAdapter, TrendingtagResponse.TrendTagsBean> {

    private ImageView headImage;

    @Override
    Observable<TrendingtagResponse> initApi() {
        mRecyclerView.addItemDecoration(
                new GridItemDecoration(3,
                        DensityUtil.dp2px(8.0f),
                        true));
        return Retro.getAppApi().getHotTags(
                "Bearer " + mUserModel.getResponse().getAccess_token(),
                "for_android");
        //return null;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_hot_tag;
    }

    @Override
    boolean showToolbar() {
        return true;
    }


    @Override
    View initView(View v) {
        super.initView(v);
        headImage = v.findViewById(R.id.head_image);
        mToolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        CollapsingToolbarLayout collapsingToolbarLayout = v.findViewById(R.id.toolbar_layout);
        AppBarLayout appBarLayout = v.findViewById(R.id.app_bar);
        appBarLayout.addOnOffsetChangedListener(new AppBarStateChangeListener() {
            @Override
            public void onStateChanged(AppBarLayout appBarLayout, State state) {
                if (state == State.EXPANDED) {
                } else if (state == State.COLLAPSED) {
                    mToolbar.setTitle("热门标签");
                } else {
                    mToolbar.setTitle(" ");
                }
            }
        });
        return v;
    }

    @Override
    Observable<TrendingtagResponse> initNextApi() {
        return null;
    }

    @Override
    boolean hasNext() {
        return false;
    }

    @Override
    void initAdapter() {
        mAdapter = new HotTagAdapter(allItems, mContext);
        GridLayoutManager layoutManager = new GridLayoutManager(mContext, 3);
        mRecyclerView.setLayoutManager(layoutManager);

        Glide.with(mContext)
                .load(GlideUtil.getLargeImage(allItems.get((int) (Math.random() * allItems.size())).getIllust()))
                //.apply(bitmapTransform(new BlurTransformation(15, 3)))
                .into(headImage);
    }
}
