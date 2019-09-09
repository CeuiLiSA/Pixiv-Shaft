package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;

import com.ToxicBakery.viewpager.transforms.AccordionTransformer;
import com.ToxicBakery.viewpager.transforms.BackgroundToForegroundTransformer;
import com.ToxicBakery.viewpager.transforms.CubeInTransformer;
import com.ToxicBakery.viewpager.transforms.CubeOutTransformer;
import com.ToxicBakery.viewpager.transforms.DefaultTransformer;
import com.ToxicBakery.viewpager.transforms.DepthPageTransformer;
import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.ToxicBakery.viewpager.transforms.FlipHorizontalTransformer;
import com.ToxicBakery.viewpager.transforms.FlipVerticalTransformer;
import com.ToxicBakery.viewpager.transforms.ForegroundToBackgroundTransformer;
import com.ToxicBakery.viewpager.transforms.RotateUpTransformer;
import com.ToxicBakery.viewpager.transforms.ScaleInOutTransformer;
import com.ToxicBakery.viewpager.transforms.StackTransformer;
import com.ToxicBakery.viewpager.transforms.TabletTransformer;
import com.ToxicBakery.viewpager.transforms.ZoomInTransformer;
import com.ToxicBakery.viewpager.transforms.ZoomOutTransformer;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.Toolbar;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.gson.Gson;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.sufficientlysecure.htmltextview.HtmlTextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustDetailAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.GifCreate;
import ceui.lisa.download.GifDownload;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.GifResponse;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.ShareIllust;
import ceui.lisa.view.LinearItemDecorationNoLRTB;
import ceui.lisa.view.TouchRecyclerView;
import de.hdodenhof.circleimageview.CircleImageView;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.glide.transformations.BlurTransformation;
import me.next.tagview.TagCloudView;

import static ceui.lisa.activities.Shaft.sUserModel;
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
    private FloatingActionButton download;

    public static FragmentSingleIllust newInstance(IllustsBean illustsBean) {
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
        RefreshLayout refreshLayout = v.findViewById(R.id.refreshLayout);
        refreshLayout.setEnableLoadMore(true);
        refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        imageView = v.findViewById(R.id.bg_image);
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.share);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_share) {
                    new ShareIllust(mContext, illust) {
                        @Override
                        public void onPrepare() {

                        }

                        @Override
                        public void onExecuteSuccess(Void aVoid) {

                        }

                        @Override
                        public void onExecuteFail(Exception e) {

                        }
                    }.execute();
                    return true;
                }
                return false;
            }
        });
        toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        toolbar.setTitle(illust.getTitle() + "  ");
        toolbar.setTitleTextAppearance(mContext, R.style.toolbarText);
        toolbar.setNavigationOnClickListener(view -> getActivity().finish());

        download = v.findViewById(R.id.download);
        download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (illust.isGif()) {
                    GifCreate.createGif(illust);
                } else {
                    if (illust.getPage_count() == 1) {
                        IllustDownload.downloadIllust(illust);
                    } else {
                        IllustDownload.downloadAllIllust(illust);
                    }
                }
            }
        });
        File file = FileCreator.createIllustFile(illust);
        if (file.exists()) {
            download.setImageResource(R.drawable.ic_has_download);
        }

        FloatingActionButton related = v.findViewById(R.id.related);
        related.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "相关作品");
                intent.putExtra(TemplateFragmentActivity.EXTRA_ILLUST_ID, illust.getId());
                intent.putExtra(TemplateFragmentActivity.EXTRA_ILLUST_TITLE, illust.getTitle());
                startActivity(intent);
            }
        });
        FloatingActionButton comment = v.findViewById(R.id.comment);
        comment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "相关评论");
                intent.putExtra(TemplateFragmentActivity.EXTRA_ILLUST_ID, illust.getId());
                intent.putExtra(TemplateFragmentActivity.EXTRA_ILLUST_TITLE, illust.getTitle());
                startActivity(intent);
            }
        });
        FloatingActionButton star = v.findViewById(R.id.post_like);

        if (illust.isIs_bookmarked()) {
            star.setImageResource(R.drawable.ic_favorite_accent_24dp);
        } else {
            star.setImageResource(R.drawable.ic_favorite_black_24dp);
        }
        star.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (illust.isIs_bookmarked()) {
                    star.setImageResource(R.drawable.ic_favorite_black_24dp);
                } else {
                    star.setImageResource(R.drawable.ic_favorite_accent_24dp);
                }
                PixivOperate.postLike(illust, sUserModel, FragmentLikeIllust.TYPE_PUBLUC);
            }
        });

        star.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (illust.isIs_bookmarked()) {

                } else {
                    Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                    intent.putExtra(TemplateFragmentActivity.EXTRA_ILLUST_ID, illust.getId());
                    intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "按标签收藏");
                    startActivity(intent);
                }
                return true;
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
        String illustSize = "尺寸：" + illust.getSize();
        TextView illustPx = v.findViewById(R.id.illust_px);
        illustPx.setText(illustSize);
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
        HtmlTextView description = v.findViewById(R.id.description);
        if (!TextUtils.isEmpty(illust.getCaption())) {
            description.setVisibility(View.VISIBLE);
            description.setHtml(illust.getCaption());
            //description.setClickableTableSpan(new WebSiteSpan());
            //description.setMovementMethod(LinkMovementMethod.getInstance());
        } else {
            description.setVisibility(View.GONE);
        }
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

        if (illust.getUser().isIs_followed()) {
            follow.setText("取消關注");
            follow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finalFollow.setText("+ 關注");
                    PixivOperate.postUnFollowUser(illust.getUser().getId());
                }
            });
        } else {
            follow.setText("+ 關注");
            follow.setOnClickListener(v1 -> {
                finalFollow.setText("取消關注");
                PixivOperate.postFollowUser(illust.getUser().getId(), "public");
            });
            follow.setOnLongClickListener(v1 -> {
                finalFollow.setText("取消關注");
                PixivOperate.postFollowUser(illust.getUser().getId(), "private");
                return true;
            });
        }
        return v;
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
                if (viewType == 0) {
                    Intent intent = new Intent(mContext, ImageDetailActivity.class);
                    intent.putExtra("illust", illust);
                    intent.putExtra("dataType", "二级详情");
                    intent.putExtra("index", position);
                    startActivity(intent);
                } else if (viewType == 1) {
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
        if (illust.getType().equals("ugoira") && mDetailAdapter != null) {
            mDetailAdapter.setPlayGif(false);
        }
    }

    public void getGifUrl() {
        Common.showToast("获取图组ZIP地址");
        Retro.getAppApi().getGifPackage(sUserModel.getResponse().getAccess_token(), illust.getId())
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
            if (Shaft.sSettings.isSaveViewHistory()) {
                insertViewHistory();
            }
            if (illust.getType().equals("ugoira") && mDetailAdapter != null) {
                mDetailAdapter.startGif();
            }
        } else {
            if (illust.getType().equals("ugoira") && mDetailAdapter != null) {
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
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(illustHistoryEntity);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
        if (className.contains(event.getReceiver())) {
            mDetailAdapter.startGif();
            return;
        }

        if (event.getReceiver().contains("FragmentSingleIllust starIllust")) {
            illust.setIs_bookmarked(true);
            ((FloatingActionButton) parentView.findViewById(R.id.post_like))
                    .setImageResource(R.drawable.ic_favorite_accent_24dp);
        }


        if(event.getReceiver().equals("FragmentSingleIllust option2")){
            if((int) event.getObject() == illust.getId()) {
                download.setImageResource(R.drawable.ic_has_download);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
