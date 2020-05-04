package ceui.lisa.fragments;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.DisplayCutout;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityOptionsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.IllustDetailAdapter;
import ceui.lisa.databinding.FragmentSingleIllustBinding;
import ceui.lisa.dialogs.MuteDialog;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.GifCreate;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.ShareIllust;
import ceui.lisa.view.LinearItemDecorationNoLRTB;
import ceui.lisa.view.ScrollChange;
import ceui.lisa.viewmodel.Dust;
import jp.wasabeef.glide.transformations.BlurTransformation;
import me.next.tagview.TagCloudView;

import static ceui.lisa.activities.Shaft.sUserModel;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

/**
 * 插画详情
 */
public class FragmentSingleIllust extends BaseFragment<FragmentSingleIllustBinding> {

    private IllustsBean illust;
    private IllustDetailAdapter mDetailAdapter;
    private int index;

    public static FragmentSingleIllust newInstance(int index) {
        Common.showLog("FragmentSingleIllust " + " newInstance " + index);
        Bundle args = new Bundle();
        args.putInt(Params.INDEX, index);
        FragmentSingleIllust fragment = new FragmentSingleIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        index = bundle.getInt(Params.INDEX);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_single_illust;
    }

    private void loadImage() {

        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (currentNightMode) {
            case Configuration.UI_MODE_NIGHT_NO:
            case Configuration.UI_MODE_NIGHT_UNDEFINED:
                Glide.with(mContext)
                        .load(GlideUtil.getSquare(illust))
                        .apply(bitmapTransform(new BlurTransformation(25, 3)))
                        .transition(withCrossFade())
                        .into(baseBind.bgImage);
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                baseBind.bgImage.setImageResource(R.color.black);
                break;
        }


        mDetailAdapter = new IllustDetailAdapter(illust, mActivity);
        mDetailAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 0) {
                    Intent intent = new Intent(mContext, ImageDetailActivity.class);
                    intent.putExtra("illust", illust);
                    intent.putExtra("dataType", "二级详情");
                    intent.putExtra("index", position);
                    if (Shaft.sSettings.isFirstImageSize()) {
                        mContext.startActivity(intent);
                    } else {
                        Bundle bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(mActivity,
                                v, "big_image_" + position).toBundle();
                        startActivity(intent, bundle);
                    }
                } else if (viewType == 1) {

                }
            }
        });
        baseBind.recyclerView.setAdapter(mDetailAdapter);
    }

    @Override
    void initData() {
        loadImage();
    }

    @Override
    public void initView(View view) {
        Dust dust = new ViewModelProvider(mActivity).get(Dust.class);
        if (dust == null || dust.getDust() == null ||
                dust.getDust().getValue() == null || dust.getDust().getValue().size() == 0) {
            mActivity.finish();
            return;
        }
        illust = dust.getDust().getValue().get(index);

        baseBind.refreshLayout.setEnableLoadMore(true);
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        baseBind.toolbar.setTitle(illust.getTitle() + "  ");
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());

        if (illust != null && illust.getId() > 0) {
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
                    } else if (menuItem.getItemId() == R.id.action_dislike) {
                        MuteDialog muteDialog = MuteDialog.newInstance(illust);
                        muteDialog.show(getChildFragmentManager(), "MuteDialog");
                    } else if (menuItem.getItemId() == R.id.action_preview) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "开发者预览");
                        startActivity(intent);
                    }
                    return false;
                }
            });

            baseBind.download.setOnClickListener(v -> {
                if (illust.isGif()) {
                    GifCreate.createGif(illust);
                } else {
                    if (illust.getPage_count() == 1) {
                        IllustDownload.downloadIllust(mActivity, illust);
                    } else {
                        IllustDownload.downloadAllIllust(mActivity, illust);
                    }
                }
            });
            File file = FileCreator.createIllustFile(illust);
            if (file.exists()) {
                baseBind.download.setImageResource(R.drawable.ic_has_download);
            }
            baseBind.userName.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Common.copy(mContext, String.valueOf(illust.getUser().getName()));
                    return true;
                }
            });
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
            baseBind.illustLike.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, illust);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "喜欢这个作品的用户");
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
                    if (!illust.isIs_bookmarked()) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(Params.ILLUST_ID, illust.getId());
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                        startActivity(intent);
                    }
                    return true;
                }
            });
            baseBind.userHead.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, UActivity.class);
                    intent.putExtra(Params.USER_ID, illust.getUser().getId());
                    startActivity(intent);
                }
            });
            baseBind.userName.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, UActivity.class);
                    intent.putExtra(Params.USER_ID, illust.getUser().getId());
                    startActivity(intent);
                }
            });

            baseBind.follow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (illust.getUser().isIs_followed()) {
                        baseBind.follow.setText("+ 关注");
                        PixivOperate.postUnFollowUser(illust.getUser().getId());
                        illust.getUser().setIs_followed(false);
                    } else {
                        baseBind.follow.setText("取消关注");
                        PixivOperate.postFollowUser(illust.getUser().getId(), FragmentLikeIllust.TYPE_PUBLUC);
                        illust.getUser().setIs_followed(true);
                    }
                }
            });

            baseBind.follow.setOnLongClickListener(v1 -> {
                if (illust.getUser().isIs_followed()) {

                } else {
                    baseBind.follow.setText("取消关注");
                    illust.getUser().setIs_followed(true);
                    PixivOperate.postFollowUser(illust.getUser().getId(), FragmentLikeIllust.TYPE_PRIVATE);
                }
                return true;
            });
        }

        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(illust.getUser().getProfile_image_urls().getMedium()))
                .into(baseBind.userHead);

        baseBind.userName.setText(illust.getUser().getName());

        SpannableString sizeString = new SpannableString(String.format("尺寸：%s",
                illust.getSize()));
        sizeString.setSpan(new ForegroundColorSpan(mContext.getResources().getColor(R.color.colorPrimary)),
                3, illust.getSize().length() + 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        baseBind.illustPx.setText(sizeString);
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < illust.getTags().size(); i++) {
            String temp = illust.getTags().get(i).getName();
            tags.add(temp);
        }
        baseBind.illustTag.setOnTagClickListener(new TagCloudView.OnTagClickListener() {
            @Override
            public void onTagClick(int position) {
                Intent intent = new Intent(mContext, SearchActivity.class);
                intent.putExtra(Params.KEY_WORD, illust.getTags().get(position).getName());
                intent.putExtra(Params.INDEX, 0);
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

        ScrollChange layoutManager = new ScrollChange(mContext);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.setNestedScrollingEnabled(true);
        baseBind.recyclerView.addItemDecoration(new LinearItemDecorationNoLRTB(DensityUtil.dp2px(1.0f)));

        if (illust.getUser().isIs_followed()) {
            baseBind.follow.setText("取消关注");
        } else {
            baseBind.follow.setText("+ 关注");
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
        if (illust.getPage_count() == 1) {
            baseBind.pSize.setVisibility(View.GONE);
            baseBind.darkBlank.setVisibility(View.INVISIBLE);
            baseBind.seeAll.setVisibility(View.INVISIBLE);
            baseBind.illustList.open();
        } else {
            baseBind.pSize.setVisibility(View.VISIBLE);
            baseBind.pSize.setText(illust.getPage_count() + "P");
            baseBind.darkBlank.setVisibility(View.VISIBLE);
            baseBind.seeAll.setVisibility(View.VISIBLE);
            baseBind.illustList.close();
            baseBind.seeAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (baseBind.illustList.isExpand()) {
                        baseBind.illustList.close();
                        baseBind.seeAll.setText("点击展开");
                    } else {
                        baseBind.illustList.open();
                        baseBind.seeAll.setText("点击折叠");
                    }
                }
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        //如果是GIF，停止播放
        if (illust != null && "ugoira".equals(illust.getType()) && mDetailAdapter != null) {
            mDetailAdapter.nowStopGif();
        }
    }

    @Override
    public void vertical() {
        //竖屏
        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight + Shaft.toolbarHeight;
        baseBind.head.setLayoutParams(headParams);

        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        Common.showLog(illust.getTitle() + "screen 竖屏");
    }

    @Override
    public void horizon() {
        //横屏
        Common.showLog(illust.getTitle() + "screen 横屏");

        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight * 3 / 5 + Shaft.toolbarHeight;
        baseBind.head.setLayoutParams(headParams);
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (illust != null) {
            if (isVisibleToUser) {
                if ("ugoira".equals(illust.getType()) && mDetailAdapter != null) {
                    mDetailAdapter.nowPlayGif();
                }
            } else {
                if ("ugoira".equals(illust.getType()) && mDetailAdapter != null) {
                    mDetailAdapter.nowStopGif();
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
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
    public boolean eventBusEnable() {
        return true;
    }
}
