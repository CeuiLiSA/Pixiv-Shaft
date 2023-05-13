package ceui.lisa.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.scwang.smart.refresh.header.FalsifyFooter;
import com.scwang.smart.refresh.header.FalsifyHeader;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.cache.Cache;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.databinding.FragmentUgoraBinding;
import ceui.lisa.dialogs.MuteDialog;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.file.OutPut;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.Back;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.CallBackReceiver;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.ShareIllust;
import ceui.lisa.viewmodel.AppLevelViewModel;
import jp.wasabeef.glide.transformations.BlurTransformation;
import rxhttp.wrapper.entity.Progress;

import static ceui.lisa.utils.SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD;
import static ceui.lisa.utils.ShareIllust.URL_Head;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

/**
 * 插画详情
 */
public class FragmentSingleUgora extends BaseFragment<FragmentUgoraBinding> {

    private IllustsBean illust;
    private CallBackReceiver mReceiver, mPlayReceiver;

    public static FragmentSingleUgora newInstance(IllustsBean illust) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, illust);
        FragmentSingleUgora fragment = new FragmentSingleUgora();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illust = (IllustsBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_ugora;
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

        int imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                2 * mContext.getResources().getDimensionPixelSize(R.dimen.twelve_dp));
        ViewGroup.LayoutParams params = baseBind.illustImage.getLayoutParams();
        params.height = imageSize * illust.getHeight() / illust.getWidth();
        params.width = imageSize;
        baseBind.illustImage.setLayoutParams(params);

        Glide.with(mContext)
                .asDrawable()
                .load(GlideUtil.getLargeImage(illust))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(new SimpleTarget<Drawable>() {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                        baseBind.illustImage.setImageDrawable(resource);
                    }
                });
    }

    @Override
    protected void initData() {
        if (illust != null) {
            loadImage();
        }

        {
            IntentFilter intentFilter = new IntentFilter();
            mReceiver = new CallBackReceiver(new BaseReceiver.CallBack() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Bundle bundle = intent.getExtras();
                    if (bundle != null) {
                        int id = bundle.getInt(Params.ID);
                        if (illust.getId() == id) {
                            boolean isLiked = bundle.getBoolean(Params.IS_LIKED);
                            if (isLiked) {
                                illust.setIs_bookmarked(true);
                                baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp);
                            } else {
                                illust.setIs_bookmarked(false);
                                baseBind.postLike.setImageResource(R.drawable.ic_favorite_black_24dp);
                            }
                        }
                    }
                }
            });
            intentFilter.addAction(Params.LIKED_ILLUST);
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
        }

        {
            IntentFilter intentFilter = new IntentFilter();
            mPlayReceiver = new CallBackReceiver(new BaseReceiver.CallBack() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                    Bundle bundle = intent.getExtras();
                    if (bundle != null) {
                        int id = bundle.getInt(Params.ID);
                        if (illust.getId() == id) {
                            nowPlayGif();
                        }
                    }
                }
            });
            intentFilter.addAction(Params.PLAY_GIF);
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mPlayReceiver, intentFilter);
        }

        PixivOperate.setBack(illust.getId(), new Back() {
            @Override
            public void invoke(float progress) {
                baseBind.progressLayout.donutProgress.setProgress((float) (Math.round(progress * 100)));
            }
        });

//        File gifFile = new LegacyFile().gifResultFile(mContext, illust);
//        if (gifFile.exists() && gifFile.length() > 1024) {
//            Common.showLog(illust.getTitle() + " GIF文件已存在，直接播放");
//            baseBind.playGif.setVisibility(View.INVISIBLE);
//            baseBind.progressLayout.donutProgress.setVisibility(View.INVISIBLE);
//            Glide.with(mContext)
//                    .load(gifFile)
//                    .into(baseBind.illustImage);
//        }
    }

    @Override
    public void onDestroy() {
        try {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mReceiver);
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mPlayReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }

    public void nowPlayGif() {
        File gifFile = LegacyFile.gifResultFile(mContext, illust);
        PixivOperate.setBack(illust.getId(), new Back() {
            @Override
            public void invoke(float progress) {
                baseBind.progressLayout.donutProgress.setProgress((float) (Math.round(progress * 100)));
            }
        });
        Common.showLog("nowPlayGif " + gifFile.getPath());
        if (gifFile.exists() && gifFile.length() > 1024) {
            Common.showLog("GIF文件已存在，直接播放");
            baseBind.playGif.setVisibility(View.INVISIBLE);
            baseBind.progressLayout.donutProgress.setVisibility(View.INVISIBLE);
            Glide.with(mContext)
                    .asGif()
                    .load(gifFile)
                    .placeholder(baseBind.illustImage.getDrawable())
                    .into(baseBind.illustImage);
        } else {
            boolean hasDownload = Shaft.getMMKV().decodeBool(Params.ILLUST_ID + "_" + illust.getId());
            File zipFile = LegacyFile.gifZipFile(mContext, illust);
            if (hasDownload && zipFile.exists() && zipFile.length() > 1024) {
                baseBind.playGif.setVisibility(View.INVISIBLE);
                baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                PixivOperate.unzipAndPlay(mContext, illust);
            } else {
                Common.showToast("获取GIF信息");
                baseBind.progress.setVisibility(View.VISIBLE);
                PixivOperate.getGifInfo(illust, new ErrorCtrl<GifResponse>() {
                    @Override
                    public void next(GifResponse gifResponse) {
                        baseBind.progress.setVisibility(View.INVISIBLE);
                        Cache.get().saveModel(Params.ILLUST_ID + "_" + illust.getId(), gifResponse);
                        Common.showToast("下载GIF文件");
                        DownloadItem downloadItem = IllustDownload.downloadGif(gifResponse, illust);
                        Manager.get().setCallback(downloadItem.getUuid(), new Callback<Progress>() {
                            @Override
                            public void doSomething(Progress t) {
                                try {
                                    if (illust.getId() == Manager.get().getCurrentIllustID()) {
                                        baseBind.playGif.setVisibility(View.INVISIBLE);
                                        baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                                        baseBind.progressLayout.donutProgress.setProgress(t.getProgress());
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                });
            }
        }
    }

    @Override
    public void initView() {
        if (illust == null) {
            return;
        }

        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());

        if (illust.getId() == 0) {
            baseBind.toolbar.setTitle(R.string.string_206);
            baseBind.refreshLayout.setVisibility(View.INVISIBLE);
            return;
        }

        if (illust.getId() == Manager.get().getCurrentIllustID()) {
            Manager.get().setCallback(new Callback<Progress>() {
                @Override
                public void doSomething(Progress t) {
                    try {
                        if (illust.getId() == Manager.get().getCurrentIllustID()) {
                            baseBind.playGif.setVisibility(View.INVISIBLE);
                            baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                            baseBind.progressLayout.donutProgress.setProgress(t.getProgress());
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }


        baseBind.playGif.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                nowPlayGif();
            }
        });

        baseBind.refreshLayout.setVisibility(View.VISIBLE);
        baseBind.refreshLayout.setEnableLoadMore(true);
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        // baseBind.toolbar.setTitle(illust.getTitle());
        baseBind.title.setText(illust.getTitle());
        baseBind.title.setOnLongClickListener(v -> {
            Common.copy(mContext, illust.getTitle());
            return true;
        });

        baseBind.toolbar.inflateMenu(R.menu.share);
        baseBind.toolbar.getMenu().findItem(R.id.action_show_original).setVisible(false);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_share) {
                    new ShareIllust(mContext, illust) {
                        @Override
                        public void onPrepare() {

                        }
                    }.execute();
                    return true;
                } else if (menuItem.getItemId() == R.id.action_dislike) {
                    MuteDialog muteDialog = MuteDialog.newInstance(illust);
                    muteDialog.show(getChildFragmentManager(), "MuteDialog");
                    return true;
                } else if (menuItem.getItemId() == R.id.action_copy_link) {
                    String url = URL_Head + illust.getId();
                    Common.copy(mContext, url);
                    return true;
                } else if (menuItem.getItemId() == R.id.action_show_original) {
//                    baseBind.recyclerView.setAdapter(new IllustAdapter(mContext, illust,
//                            recyHeight, true));
                    return true;
                } else if (menuItem.getItemId() == R.id.action_mute_illust) {
                    PixivOperate.muteIllust(illust);
                    return true;
                }
                return false;
            }
        });

        baseBind.download.setOnClickListener(v -> {
            File gifFile = LegacyFile.gifResultFile(mContext, illust);
            if (gifFile.exists() && gifFile.length() > 1024) {
                OutPut.outPutGif(mContext, gifFile, illust);
                if(Shaft.sSettings.isAutoPostLikeWhenDownload() && !illust.isIs_bookmarked()){
                    PixivOperate.postLikeDefaultStarType(illust);
                }
            } else {
                IllustDownload.downloadGif(illust);
                Common.showToast('1' + requireContext().getString(R.string.has_been_added));
            }
        });
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
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp);
        } else {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_black_24dp);
        }
        baseBind.postLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (illust.isIs_bookmarked()) {
                    baseBind.postLike.setImageResource(R.drawable.ic_favorite_black_24dp);
                } else {
                    baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp);
                }
                PixivOperate.postLikeDefaultStarType(illust);
            }
        });
        baseBind.postLike.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.ILLUST_ID, illust.getId());
                intent.putExtra(Params.DATA_TYPE, Params.TYPE_ILLUST);
                intent.putExtra(Params.TAG_NAMES, illust.getTagNames());
                intent.putExtra(Params.LAST_CLASS, getClass().getSimpleName());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                startActivity(intent);
                return true;
            }
        });
        baseBind.userHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, UserActivity.class);
                intent.putExtra(Params.USER_ID, illust.getUser().getId());
                startActivity(intent);
            }
        });
        baseBind.userName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, UserActivity.class);
                intent.putExtra(Params.USER_ID, illust.getUser().getId());
                startActivity(intent);
            }
        });

        baseBind.follow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Integer integerValue = Shaft.appViewModel.getFollowUserLiveData(illust.getUser().getId()).getValue();
                if (AppLevelViewModel.FollowUserStatus.isFollowed(integerValue)) {
                    PixivOperate.postUnFollowUser(illust.getUser().getId());
                    illust.getUser().setIs_followed(false);
                } else {
                    PixivOperate.postFollowUser(illust.getUser().getId(), Params.TYPE_PUBLIC);
                    illust.getUser().setIs_followed(true);
                }
            }
        });

        baseBind.follow.setOnLongClickListener(v1 -> {
            Integer integerValue = Shaft.appViewModel.getFollowUserLiveData(illust.getUser().getId()).getValue();
            if (!AppLevelViewModel.FollowUserStatus.isFollowed(integerValue)) {
                illust.getUser().setIs_followed(true);
            }
            PixivOperate.postFollowUser(illust.getUser().getId(), Params.TYPE_PRIVATE);
            return true;
        });

        Glide.with(mContext)
                .load(GlideUtil.getUrl(illust.getUser().getProfile_image_urls().getMedium()))
                .into(baseBind.userHead);

        baseBind.userName.setText(illust.getUser().getName());

        SpannableString sizeString = new SpannableString(getString(R.string.string_193, illust.getWidth(), illust.getHeight()));
        int currentPrimaryColorId = Common.resolveThemeAttribute(mContext, R.attr.colorPrimary);
        sizeString.setSpan(new ForegroundColorSpan(currentPrimaryColorId),
                sizeString.length()-illust.getSize().length(), sizeString.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        baseBind.illustPx.setText(sizeString);

        baseBind.illustTag.setAdapter(new TagAdapter<TagsBean>(illust.getTags()) {
            @Override
            public View getView(FlowLayout parent, int position, TagsBean s) {
                TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_line_text_new,
                        parent, false);
                String tag = s.getName();
                if (!TextUtils.isEmpty(s.getTranslated_name())) {
                    tag = tag + "/" + s.getTranslated_name();
                }
                tv.setText(tag);
                return tv;
            }
        });
        baseBind.illustTag.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
            @Override
            public boolean onTagClick(View view, int position, FlowLayout parent) {
                Intent intent = new Intent(mContext, SearchActivity.class);
                intent.putExtra(Params.KEY_WORD, illust.getTags().get(position).getName());
                intent.putExtra(Params.INDEX, 0);
                startActivity(intent);
                return true;
            }
        });
        baseBind.illustTag.setOnTagLongClickListener(new TagFlowLayout.OnTagLongClickListener() {
            @Override
            public boolean onTagLongClick(View view, int position, FlowLayout parent) {
                // 弹出菜单：固定+复制
                String tagName = illust.getTags().get(position).getName();
                SearchEntity searchEntity = PixivOperate.getSearchHistory(tagName, SEARCH_TYPE_DB_KEYWORD);
                boolean isPinned = searchEntity != null && searchEntity.isPinned();
                new QMUIDialog.MessageDialogBuilder(mContext)
                        .setTitle(tagName)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addAction(isPinned ? getString(R.string.string_443) : getString(R.string.string_442), new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                PixivOperate.insertPinnedSearchHistory(tagName, SEARCH_TYPE_DB_KEYWORD, !isPinned);
                                Common.showToast(R.string.operate_success);
                                dialog.dismiss();
                            }
                        })
                        .addAction(getString(R.string.string_120), new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                Common.copy(mContext, tagName);
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show();
                return true;
            }
        });

        if (!TextUtils.isEmpty(illust.getCaption())) {
            baseBind.description.setVisibility(View.VISIBLE);
            baseBind.description.setHtml(illust.getCaption());
        } else {
            baseBind.description.setVisibility(View.GONE);
        }
        baseBind.illustDate.setText(Common.getLocalYYYYMMDDHHMMString(illust.getCreate_date()));
        baseBind.illustView.setText(String.valueOf(illust.getTotal_view()));
        baseBind.illustLike.setText(String.valueOf(illust.getTotal_bookmarks()));

        SpannableString userString = new SpannableString(getString(R.string.string_195, illust.getUser().getId()));
        userString.setSpan(new ForegroundColorSpan(currentPrimaryColorId),
                userString.length()-String.valueOf(illust.getUser().getId()).length(), userString.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        baseBind.userId.setText(userString);
        baseBind.userId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.copy(mContext, String.valueOf(illust.getUser().getId()));
            }
        });
        SpannableString illustString = new SpannableString(getString(R.string.string_194, illust.getId()));
        illustString.setSpan(new ForegroundColorSpan(currentPrimaryColorId),
                illustString.length()-String.valueOf(illust.getId()).length(), illustString.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        baseBind.illustId.setText(illustString);
        baseBind.illustId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.copy(mContext, String.valueOf(illust.getId()));
            }
        });

        Shaft.appViewModel.getFollowUserLiveData(illust.getUser().getId()).observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                updateFollowUserUI(integer);
            }
        });
    }

    @Override
    public void vertical() {
        //竖屏
        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight + Shaft.toolbarHeight;
        baseBind.head.setLayoutParams(headParams);
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
    }

    @Override
    public void horizon() {
        //横屏
        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight * 3 / 5 + Shaft.toolbarHeight;
        baseBind.head.setLayoutParams(headParams);
    }

    private void updateFollowUserUI(int status){
        if(AppLevelViewModel.FollowUserStatus.isFollowed(status)){
            baseBind.follow.setText(R.string.string_177);
        }else{
            baseBind.follow.setText(R.string.string_4);
        }
    }
}
