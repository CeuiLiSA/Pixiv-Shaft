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

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.adapters.IllustDetailAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.GifCreate;
import ceui.lisa.download.GifDownload;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.response.GifResponse;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.UserDetailResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.ExpandCard;
import ceui.lisa.view.LinearItemDecorationNoLRTB;
import ceui.lisa.view.TouchRecyclerView;
import de.hdodenhof.circleimageview.CircleImageView;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
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
    private IllustDetailAdapter mDetailAdapter;

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
        TextView downloadText = v.findViewById(R.id.download_text);
        if(illust.isGif() && illust.getPage_count() == 1){
            downloadText.setText("保存动图");
        }
        download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(illust.isGif()){
                    GifCreate.createGif(illust);
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

        follow = v.findViewById(R.id.follow);
        TextView finalFollow = follow;
        follow.setOnClickListener(v1 -> {
            PixivOperate.followOrUnfollowClick(illust.getUser().getId(), finalFollow);
        });
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
        mDetailAdapter = new IllustDetailAdapter(illust, mContext);
        mDetailAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if(viewType == 0) {
                    Intent intent = new Intent(mContext, ImageDetailActivity.class);
                    intent.putExtra("illust", illust);
                    intent.putExtra("index", position);
                    startActivity(intent);
                }else if(viewType == 1){
                    getGifUrl();
                }
            }
        });
        mRecyclerView.setAdapter(mDetailAdapter);
    }

    @Override
    void initData() {
        loadImage();
    }

    @Override
    public void onPause() {
        super.onPause();
        if(illust.getType().equals("ugoira") && mDetailAdapter != null){
            mDetailAdapter.setPlayGif(false);
        }
    }

    public void getGifUrl(){
        Common.showToast("获取图组ZIP地址");
        Retro.getAppApi().getGifPackage(mUserModel.getResponse().getAccess_token(), illust.getId())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<GifResponse>() {
                    @Override
                    public void onNext(GifResponse gifResponse) {
                        GifDownload.downloadGif(gifResponse, illust);
                    }
                });
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser) {
            insertViewHistory();
            if(illust.getType().equals("ugoira") && mDetailAdapter != null){
                mDetailAdapter.startGif();
            }
        }else {
            if(illust.getType().equals("ugoira") && mDetailAdapter != null){
                mDetailAdapter.setPlayGif(false);
            }
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

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
        if(className.contains(event.getReceiver())) {
            mDetailAdapter.startGif();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);
        Common.showLog(className + "EVENTBUS 注册了");
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
        Common.showLog(className + "EVENTBUS 取消注册了");
    }
}
