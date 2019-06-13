package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.widget.CardView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringChain;
import com.google.gson.Gson;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.adapters.IllustDetailAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.view.ExpandCard;
import ceui.lisa.view.LinearItemDecorationNoLRTB;
import ceui.lisa.view.TouchRecyclerView;
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
    private ImageView imageView;
    private TagCloudView mTagCloudView;
    private TouchRecyclerView mRecyclerView;
    private TextView seeAll;

    public static FragmentSingleIllust newInstance(IllustsBean illustsBean, Bundle bundle) {
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
        Common.showLog(className + new Gson().toJson(illust));
        imageView = v.findViewById(R.id.bg_image);
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

        CardView download = v.findViewById(R.id.download_illust);
        download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if(illust.getType().equals("ugoira")){



                }else {


                    if (illust.getPage_count() == 1) {
                        IllustDownload.downloadIllust(illust);
                    } else {
                        IllustDownload.downloadAllIllust(illust);
                    }
                }
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



        initCardSize();




        TextView userName = v.findViewById(R.id.user_name);
        TextView follow = v.findViewById(R.id.follow);
        if (illust.getUser().isIs_followed()) {
            follow.setText("取消关注");
        } else {
            follow.setText("+ 关注");
        }
        CircleImageView userHead = v.findViewById(R.id.user_head);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(illust.getUser().getProfile_image_urls().getMedium()))
                .into(userHead);
        userHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, UserDetailActivity.class);
                intent.putExtra("user id", illust.getUser().getId());
                startActivity(intent);
            }
        });
        userName.setText(illust.getUser().getName());
        mTagCloudView = v.findViewById(R.id.illust_tag);
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < illust.getTags().size(); i++) {
            String temp = illust.getTags().get(i).getName();
//            if(!TextUtils.isEmpty(illust.getTags().get(i).getTranslated_name())){
//                temp = temp + " (" + illust.getTags().get(i).getTranslated_name() + ")";
//            }
            tags.add(temp);
        }
        mTagCloudView.setOnTagClickListener(new TagCloudView.OnTagClickListener() {
            @Override
            public void onTagClick(int position) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD,
                        illust.getTags().get(position).getName());
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                        "搜索结果");
                startActivity(intent);
            }
        });
        mTagCloudView.setTags(tags);
        TextView date = v.findViewById(R.id.illust_date);
        TextView totalView = v.findViewById(R.id.illust_view);
        TextView like = v.findViewById(R.id.illust_like);
        date.setText(illust.getCreate_date().substring(0, 16));
        totalView.setText(String.valueOf(illust.getTotal_view()));
        like.setText(String.valueOf(illust.getTotal_bookmarks()));

        mRecyclerView = v.findViewById(R.id.recyclerView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setNestedScrollingEnabled(true);
        mRecyclerView.addItemDecoration(new LinearItemDecorationNoLRTB(DensityUtil.dp2px(1.0f)));
        return v;
    }



    private void initCardSize(){
        if(false) {
            int imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                    2 * mContext.getResources().getDimensionPixelSize(R.dimen.twelve_dp));
            int realHeight = imageSize * illust.getHeight() / illust.getWidth();


            final ExpandCard card = parentView.findViewById(R.id.illust_list);

            final String illustSize = "全部展开 (" + illust.getPage_count() + "P)";
            TextView seeAll = parentView.findViewById(R.id.see_all);
            if (illust.getPage_count() == 1) {
                seeAll.setVisibility(View.GONE);
                card.setRealHeight(realHeight);
                card.setAutoHeight(true);
            } else {
                card.setAutoHeight(false);
                card.setRealHeight(card.getMaxHeight());
                seeAll.setVisibility(View.VISIBLE);
                seeAll.setText(illustSize);
                seeAll.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (card.isExpand()) {
                            card.setExpand(false);
                            seeAll.setText(illustSize);
                        } else {
                            card.setExpand(true);
                            seeAll.setText("全部收起");
                        }
                        card.invalidate();
                    }
                });
            }
        }
    }

    private void loadImage() {
        Glide.with(mContext)
                .load(GlideUtil.getSquare(illust))
                .apply(bitmapTransform(new BlurTransformation(25, 3)))
                .transition(withCrossFade())
                .into(imageView);
        IllustDetailAdapter adapter = new IllustDetailAdapter(illust, mContext);
        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, ImageDetailActivity.class);
                intent.putExtra("illust", illust);
                intent.putExtra("index", position);
                startActivity(intent);
            }
        });
        mRecyclerView.setAdapter(adapter);
    }

    @Override
    void initData() {
        loadImage();
        //initAnime();
    }

    private void initAnime() {
        if (parentView != null) {
            mTagCloudView = parentView.findViewById(R.id.illust_tag);
            if (mTagCloudView != null) {
                SpringChain chain = SpringChain.create(100, 8, 50, 7);
                for (int i = 0; i < mTagCloudView.getChildCount(); i++) {
                    final View view = mTagCloudView.getChildAt(i);
                    chain.addSpring(new SimpleSpringListener() {
                        @Override
                        public void onSpringUpdate(Spring spring) {
                            view.setTranslationX((float) spring.getCurrentValue());
                        }

                        @Override
                        public void onSpringEndStateChange(Spring spring) {

                        }
                    });
                }
                List<Spring> springs = chain.getAllSprings();
                for (int i = 0; i < springs.size(); i++) {
                    springs.get(i).setCurrentValue(120);
                }

                chain.setControlSpringIndex(0).getControlSpring().setEndValue(0);
            }
        }
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
