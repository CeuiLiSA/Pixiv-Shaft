package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.IllustDetailAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.databinding.FragmentSingleIllustBinding;
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
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.ShareIllust;
import ceui.lisa.view.LinearItemDecorationNoLRTB;
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
public class FragmentSingleIllust extends BaseBindFragment<FragmentSingleIllustBinding> {

    private IllustsBean illust;
    private IllustDetailAdapter mDetailAdapter;

    public static FragmentSingleIllust newInstance(IllustsBean illustsBean) {
        FragmentSingleIllust fragmentSingleIllust = new FragmentSingleIllust();
        fragmentSingleIllust.setIllust(illustsBean);
        return fragmentSingleIllust;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_single_illust;
    }

    private void loadImage() {
        Glide.with(mContext)
                .load(GlideUtil.getSquare(illust))
                .apply(bitmapTransform(new BlurTransformation(25, 3)))
                .transition(withCrossFade())
                .into(baseBind.bgImage);
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
        baseBind.recyclerView.setAdapter(mDetailAdapter);
    }

    @Override
    void initData() {
        baseBind.refreshLayout.setEnableLoadMore(true);
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        baseBind.toolbar.inflateMenu(R.menu.share);

        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
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
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        baseBind.toolbar.setTitle(illust.getTitle() + "  ");
        baseBind.toolbar.setTitleTextAppearance(mContext, R.style.shadowText);
        baseBind.toolbar.setNavigationOnClickListener(view -> getActivity().finish());
        baseBind.download.setOnClickListener(new View.OnClickListener() {
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
            baseBind.download.setImageResource(R.drawable.ic_has_download);
        }

        baseBind.related.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关作品");
                intent.putExtra(Params.ILLUST_ID, illust.getId());
                intent.putExtra(Params.ILLUST_TITLE, illust.getTitle());
                startActivity(intent);
            }
        });
        baseBind.comment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论");
                intent.putExtra(Params.ILLUST_ID, illust.getId());
                intent.putExtra(Params.ILLUST_TITLE, illust.getTitle());
                startActivity(intent);
            }
        });
        if (illust.isIs_bookmarked()) {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_accent_24dp);
        } else {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_black_24dp);
        }
        baseBind.postLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (illust.isIs_bookmarked()) {
                    baseBind.postLike.setImageResource(R.drawable.ic_favorite_black_24dp);
                } else {
                    baseBind.postLike.setImageResource(R.drawable.ic_favorite_accent_24dp);
                }
                PixivOperate.postLike(illust, sUserModel, FragmentLikeIllust.TYPE_PUBLUC);
            }
        });
        baseBind.postLike.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (illust.isIs_bookmarked()) {

                } else {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.ILLUST_ID, illust.getId());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                    startActivity(intent);
                }
                return true;
            }
        });

        /**
         * 设置一个空白的imageview作为头部，作为占位,
         * 这样原图就会刚好在toolbar 下方，不会被toolbar遮住
         */
        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight + Shaft.toolbarHeight;
        baseBind.head.setLayoutParams(headParams);


        if (illust.getUser().isIs_followed()) {
            baseBind.follow.setText("取消关注");
        } else {
            baseBind.follow.setText("+ 关注");
        }
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(illust.getUser().getProfile_image_urls().getMedium()))
                .into(baseBind.userHead);
        baseBind.userHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, UActivity.class);
                intent.putExtra(Params.USER_ID, illust.getUser().getId());
                startActivity(intent);
            }
        });
        baseBind.userName.setText(illust.getUser().getName());
        baseBind.userName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, UActivity.class);
                intent.putExtra(Params.USER_ID, illust.getUser().getId());
                startActivity(intent);
            }
        });
        List<String> tags = new ArrayList<>();
        SpannableString sizeString = new SpannableString(String.format("尺寸：%s",
                illust.getSize()));
        sizeString.setSpan(new ForegroundColorSpan(mContext.getResources().getColor(R.color.colorPrimary)),
                3, illust.getSize().length() + 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        baseBind.illustPx.setText(sizeString);
        for (int i = 0; i < illust.getTags().size(); i++) {
            String temp = illust.getTags().get(i).getName();
            tags.add(temp);
        }
        baseBind.illustTag.setOnTagClickListener(new TagCloudView.OnTagClickListener() {
            @Override
            public void onTagClick(int position) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_KEYWORD,
                        illust.getTags().get(position).getName());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT,
                        "搜索结果");
                startActivity(intent);
            }
        });
        baseBind.illustTag.setTags(tags);
        if (!TextUtils.isEmpty(illust.getCaption())) {
            baseBind.description.setVisibility(View.VISIBLE);
            baseBind.description.setHtml(illust.getCaption());
        } else {
            baseBind.description.setVisibility(View.GONE);
        }
        baseBind.illustDate.setText(illust.getCreate_date().substring(0, 16));
        baseBind.illustView.setText(String.valueOf(illust.getTotal_view()));
        baseBind.illustLike.setText(String.valueOf(illust.getTotal_bookmarks()));

        LinearLayoutManager layoutManager = new LinearLayoutManager(mContext);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.setNestedScrollingEnabled(true);
        baseBind.recyclerView.addItemDecoration(new LinearItemDecorationNoLRTB(DensityUtil.dp2px(1.0f)));

        if (illust.getUser().isIs_followed()) {
            baseBind.follow.setText("取消關注");
            baseBind.follow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.follow.setText("+ 關注");
                    PixivOperate.postUnFollowUser(illust.getUser().getId());
                }
            });
        } else {
            baseBind.follow.setText("+ 關注");
            baseBind.follow.setOnClickListener(v1 -> {
                baseBind.follow.setText("取消關注");
                PixivOperate.postFollowUser(illust.getUser().getId(), FragmentLikeIllust.TYPE_PUBLUC);
            });
            baseBind.follow.setOnLongClickListener(v1 -> {
                baseBind.follow.setText("取消關注");
                PixivOperate.postFollowUser(illust.getUser().getId(), FragmentLikeIllust.TYPE_PRIVATE);
                return true;
            });
        }

        SpannableString userString = new SpannableString(String.format("用户ID：%s",
                String.valueOf(illust.getUser().getId())));
        userString.setSpan(new ForegroundColorSpan(mContext.getResources().getColor(R.color.colorPrimary)),
                5, String.valueOf(illust.getUser().getId()).length() + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        baseBind.userId.setText(userString);
        baseBind.userId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.copy(mContext, String.valueOf(illust.getUser().getId()));
            }
        });
        SpannableString illustString = new SpannableString(String.format("作品ID：%s",
                String.valueOf(illust.getId())));
        illustString.setSpan(new ForegroundColorSpan(mContext.getResources().getColor(R.color.colorPrimary)),
                5, String.valueOf(illust.getId()).length() + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        baseBind.illustId.setText(illustString);
        baseBind.illustId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.copy(mContext, String.valueOf(illust.getId()));
            }
        });
        loadImage();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (illust.getType().equals("ugoira") && mDetailAdapter != null) {
            mDetailAdapter.setPlayGif(false);
        }
    }

    private void getGifUrl() {
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

        if (event.getReceiver().equals("FragmentSingleIllust download finish")) {
            if ((int) event.getObject() == illust.getId()) {
                baseBind.download.setImageResource(R.drawable.ic_has_download);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public boolean eventBusEnable() {
        return true;
    }
}
