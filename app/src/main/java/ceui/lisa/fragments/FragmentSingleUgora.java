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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.cache.Cache;
import ceui.lisa.core.Manager;
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
import jp.wasabeef.glide.transformations.BlurTransformation;
import rxhttp.wrapper.entity.Progress;

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
        File gifFile = new LegacyFile().gifResultFile(mContext, illust);
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
            File zipFile = new LegacyFile().gifZipFile(mContext, illust);
            if (hasDownload && zipFile.exists() && zipFile.length() > 1024) {
                baseBind.playGif.setVisibility(View.INVISIBLE);
                baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                PixivOperate.unzipAndePlay(mContext, illust);
            } else {
                Common.showToast("获取GIF信息");
                baseBind.progress.setVisibility(View.VISIBLE);
                PixivOperate.getGifInfo(illust, new ErrorCtrl<GifResponse>() {
                    @Override
                    public void next(GifResponse gifResponse) {
                        baseBind.progress.setVisibility(View.INVISIBLE);
                        Cache.get().saveModel(Params.ILLUST_ID + "_" + illust.getId(), gifResponse);
                        Common.showToast("下载GIF文件");
                        IllustDownload.downloadGif(gifResponse, illust, (BaseActivity<?>) mContext);
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
        baseBind.toolbar.setTitle(illust.getTitle());

        baseBind.toolbar.inflateMenu(R.menu.share);
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
            File gifFile = new LegacyFile().gifResultFile(mContext, illust);
            if (gifFile.exists() && gifFile.length() > 1024) {
                OutPut.outPutGif(mContext, gifFile, illust);
                if(Shaft.sSettings.isAutoPostLikeWhenDownload() && !illust.isIs_bookmarked()){
                    PixivOperate.postLikeDefaultStarType(illust);
                }
            } else {
                Common.showToast("请先播放后下载");
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
                if (illust.getUser().isIs_followed()) {
                    baseBind.follow.setText(R.string.string_178);
                    PixivOperate.postUnFollowUser(illust.getUser().getId());
                    illust.getUser().setIs_followed(false);
                } else {
                    baseBind.follow.setText(R.string.string_177);
                    PixivOperate.postFollowUser(illust.getUser().getId(), Params.TYPE_PUBLUC);
                    illust.getUser().setIs_followed(true);
                }
            }
        });

        baseBind.follow.setOnLongClickListener(v1 -> {
            if (!illust.getUser().isIs_followed()) {
                baseBind.follow.setText(R.string.string_177);
                illust.getUser().setIs_followed(true);
            }
            PixivOperate.postFollowUser(illust.getUser().getId(), Params.TYPE_PRIVATE);
            return true;
        });

        Glide.with(mContext)
                .load(GlideUtil.getUrl(illust.getUser().getProfile_image_urls().getMedium()))
                .into(baseBind.userHead);

        baseBind.userName.setText(illust.getUser().getName());

        SpannableString sizeString = new SpannableString(String.format("尺寸：%s",
                illust.getSize()));
        sizeString.setSpan(new ForegroundColorSpan(R.attr.colorPrimary),
                3, illust.getSize().length() + 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                Common.copy(mContext, illust.getTags().get(position).getName());
                return true;
            }
        });

        if (!TextUtils.isEmpty(illust.getCaption())) {
            baseBind.description.setVisibility(View.VISIBLE);
            baseBind.description.setHtml(illust.getCaption());
        } else {
            baseBind.description.setVisibility(View.GONE);
        }
        baseBind.illustDate.setText(illust.getCreate_date().substring(0, 16));
        baseBind.illustView.setText(String.valueOf(illust.getTotal_view()));
        baseBind.illustLike.setText(String.valueOf(illust.getTotal_bookmarks()));


        if (illust.getUser().isIs_followed()) {
            baseBind.follow.setText(R.string.string_177);
        } else {
            baseBind.follow.setText(R.string.string_178);
        }


        SpannableString userString = new SpannableString(String.format("用户ID：%s",
                String.valueOf(illust.getUser().getId())));
        userString.setSpan(new ForegroundColorSpan(R.attr.colorPrimary),
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
        illustString.setSpan(new ForegroundColorSpan(R.attr.colorPrimary),
                5, String.valueOf(illust.getId()).length() + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        baseBind.illustId.setText(illustString);
        baseBind.illustId.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.copy(mContext, String.valueOf(illust.getId()));
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
}
