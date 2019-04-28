package ceui.lisa.fragments;

import android.content.Intent;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.SearchResultActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.HotTagAdapter;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.response.TrendingtagResponse;
import ceui.lisa.utils.AppBarStateChangeListener;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.GridItemDecoration;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.SpacesItemDecoration;
import io.reactivex.Observable;

import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

public class FragmentHotTag extends BaseListFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

    private ImageView headImage;

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRecmdIllust(mUserModel.getResponse().getAccess_token(), false);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust("Bearer " + mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
//        mRecyclerView.addItemDecoration(new GridItemDecoration(3,
//                DensityUtil.dp2px(6.0f), true));
//        GridLayoutManager layoutManager = new GridLayoutManager(mContext, 3);
//        mRecyclerView.setLayoutManager(layoutManager);
        mToolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(4.0f)));
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_hot_tag;
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
                    mToolbar.setTitle("推荐作品");
                } else {
                    mToolbar.setTitle(" ");
                }
            }
        });
        return v;
    }


    @Override
    void initAdapter() {
//        mAdapter = new HotTagAdapter(allItems, mContext);
//        mAdapter.setOnItemClickListener(new OnItemClickListener() {
//            @Override
//            public void onItemClick(View v, int position, int viewType) {
//                Intent intent = new Intent(mContext, SearchResultActivity.class);
//                intent.putExtra("key word", allItems.get(position).getTag());
//                startActivity(intent);
//            }
//        });
//        Glide.with(mContext)
//                .load(GlideUtil.getLargeImage(allItems.get((int) (Math.random() * allItems.size())).getIllust()))
//                //.apply(bitmapTransform(new BlurTransformation(15, 3)))
//                .into(headImage);

        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
        mAdapter = new IllustStagAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.getInstance().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }
}
