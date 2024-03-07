package ceui.lisa.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.scwang.smart.refresh.header.FalsifyFooter;
import com.scwang.smart.refresh.header.FalsifyHeader;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.adapters.IllustDetailAdapter;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.databinding.FragmentSingleIllustBinding;
import ceui.lisa.dialogs.MuteDialog;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.CallBackReceiver;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.ShareIllust;
import ceui.lisa.view.LinearItemDecorationNoLRTB;
import ceui.lisa.view.ScrollChange;
import ceui.lisa.viewmodel.AppLevelViewModel;
import jp.wasabeef.glide.transformations.BlurTransformation;

import static ceui.lisa.utils.SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD;
import static ceui.lisa.utils.ShareIllust.URL_Head;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

/**
 * 插画详情
 */
public class FragmentSingleIllust extends BaseFragment<FragmentSingleIllustBinding> {

    private IllustsBean illust;
    private CallBackReceiver mReceiver;

    public static FragmentSingleIllust newInstance(IllustsBean illust) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, illust);
        FragmentSingleIllust fragment = new FragmentSingleIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illust = (IllustsBean) bundle.getSerializable(Params.CONTENT);
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

        baseBind.recyclerView.setAdapter(new IllustDetailAdapter(FragmentSingleIllust.this, illust));
    }

    @Override
    protected void initData() {
        if (illust != null) {
            loadImage();
        }

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
                            baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp);
                        }
                    }
                }
            }
        });
        intentFilter.addAction(Params.LIKED_ILLUST);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        if (mReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mReceiver);
        }
        super.onDestroy();
    }

    @Override
    public void onDestroyView() {
        try {
            if (baseBind != null && baseBind.recyclerView != null) {
                baseBind.recyclerView.setAdapter(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onDestroyView();
    }

    @Override
    public void initView() {
        if (illust == null) {
            return;
        }

        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());

        if (illust.getId() == 0 || !illust.isVisible()) {
            Common.showToast(R.string.string_206);
            baseBind.refreshLayout.setVisibility(View.INVISIBLE);
            finish();
            return;
        }

        baseBind.refreshLayout.setVisibility(View.VISIBLE);
        baseBind.refreshLayout.setEnableLoadMore(true);
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        // baseBind.toolbar.setTitle(illust.getTitle());

        if(illust.getSeries() != null && !TextUtils.isEmpty(illust.getSeries().getTitle())){
            ClickableSpan clickableSpan = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情");
                    intent.putExtra(Params.MANGA_SERIES_ID, illust.getSeries().getId());
                    startActivity(intent);
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(Common.resolveThemeAttribute(mContext, R.attr.colorPrimary));
                }
            };
            SpannableString spannableString;
            String seriesString = getString(R.string.string_229);
            spannableString = new SpannableString(String.format("@%s %s",
                    seriesString, illust.getTitle()));
            spannableString.setSpan(clickableSpan, 0, seriesString.length() + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            baseBind.title.setMovementMethod(LinkMovementMethod.getInstance());
            baseBind.title.setText(spannableString);
        }else{
            baseBind.title.setText(illust.getTitle());
        }
        baseBind.title.setOnLongClickListener(v -> {
            Common.copy(mContext, illust.getTitle());
            return true;
        });

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
                    baseBind.recyclerView.setAdapter(new IllustDetailAdapter(FragmentSingleIllust.this, illust, true));
                    return true;
                } else if (menuItem.getItemId() == R.id.action_mute_illust) {
                    PixivOperate.muteIllust(illust);
                    return true;
                }
                return false;
            }
        });

        baseBind.download.setOnClickListener(v -> {
            if (illust.getPage_count() == 1) {
                IllustDownload.downloadIllustFirstPage(illust, (BaseActivity<?>) mContext);
            } else {
                IllustDownload.downloadIllustAllPages(illust, (BaseActivity<?>) mContext);
            }
            if(Shaft.sSettings.isAutoPostLikeWhenDownload() && !illust.isIs_bookmarked()){
                PixivOperate.postLikeDefaultStarType(illust);
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

        ScrollChange layoutManager = new ScrollChange(mContext);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.setNestedScrollingEnabled(true);
        baseBind.recyclerView.addItemDecoration(new LinearItemDecorationNoLRTB(DensityUtil.dp2px(1.0f)));

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
        if (illust.getPage_count() == 1) {
            baseBind.pSize.setVisibility(View.GONE);
            baseBind.darkBlank.setVisibility(View.INVISIBLE);
            baseBind.seeAll.setVisibility(View.INVISIBLE);
            baseBind.illustList.open();
        } else {
            baseBind.pSize.setVisibility(View.VISIBLE);
            baseBind.pSize.setText(String.format(Locale.getDefault(), "%dP", illust.getPage_count()));
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

        Shaft.appViewModel.getFollowUserLiveData(illust.getUser().getId()).observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                updateFollowUserUI(integer);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        checkDownload();
    }

    private void checkDownload() {
        if (illust.getPage_count() == 1) {
            if (FileCreator.isExist(illust, 0)) {
                baseBind.download.setImageResource(R.drawable.ic_has_download);
            } else {
                baseBind.download.setImageResource(R.drawable.ic_file_download_black_24dp);
            }
        }
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
