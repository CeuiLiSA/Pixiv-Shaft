package ceui.lisa.fragments;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import ceui.lisa.R;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.base.SwipeFragment;
import ceui.lisa.databinding.FragmentSlideBinding;
import ceui.lisa.dialogs.MuteDialog;
import ceui.lisa.download.GifCreate;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.ShareIllust;
import ceui.lisa.view.LinearItemDecorationNoLRTB;
import me.next.tagview.TagCloudView;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;


public class FragmentSlide extends SwipeFragment<FragmentSlideBinding> {

    private IllustsBean illust;

    public static FragmentSlide newInstance(IllustsBean illustsBean) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, illustsBean);
        FragmentSlide fragment = new FragmentSlide();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illust = (IllustsBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_slide;
    }

    @Override
    protected void initView() {
        baseBind.title.setText(illust.getTitle());
        baseBind.toolbar.inflateMenu(R.menu.share);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
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
                } else if (menuItem.getItemId() == R.id.action_preview) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "开发者预览");
                    startActivity(intent);
                }
                return false;
            }
        });
        if (illust.isIs_bookmarked()) {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp);
        } else {
            baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp);
        }
        baseBind.illustTag.setAdapter(new TagAdapter<TagsBean>(illust.getTags()) {
            @Override
            public View getView(FlowLayout parent, int position, TagsBean s) {
                TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_line_text_new,
                        parent, false);
                tv.setText(s.getName());
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
        baseBind.illustId.setText("作品ID：" + illust.getId());
        baseBind.userId.setText("画师ID：" + illust.getUser().getId());
        final BottomSheetBehavior<?> sheetBehavior = BottomSheetBehavior.from(baseBind.contentScrollView);

        baseBind.bottomBar.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                final int bottomCardHeight = baseBind.bottomBar.getHeight();
                final int deltaY = baseBind.coreLinear.getHeight() - baseBind.bottomBar.getHeight();
                sheetBehavior.setPeekHeight(bottomCardHeight, true);
                baseBind.recyclerView.setPadding(0, 0, 0, bottomCardHeight - DensityUtil.dp2px(16.0f));
                sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View bottomSheet, int newState) {

                    }

                    @Override
                    public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                        baseBind.recyclerView.setTranslationY(-deltaY * slideOffset * 0.7f);
                    }
                });

                baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
                baseBind.recyclerView.setAdapter(new IllustAdapter(mContext, illust,
                        baseBind.recyclerView.getHeight() - bottomCardHeight + DensityUtil.dp2px(16.0f)));

                baseBind.bottomBar.getViewTreeObserver().removeOnGlobalLayoutListener(this);
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
        if (!TextUtils.isEmpty(illust.getCaption())) {
            baseBind.description.setVisibility(View.VISIBLE);
            baseBind.description.setHtml(illust.getCaption());
        } else {
            baseBind.description.setVisibility(View.GONE);
        }
        baseBind.postLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (illust.isIs_bookmarked()) {
                    baseBind.postLike.setImageResource(R.drawable.ic_favorite_black_24dp);
                } else {
                    baseBind.postLike.setImageResource(R.drawable.ic_favorite_accent_24dp);
                }
                PixivOperate.postLike(illust, FragmentLikeIllust.TYPE_PUBLUC);
            }
        });
        baseBind.postLike.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!illust.isIs_bookmarked()) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.ILLUST_ID, illust.getId());
                    intent.putExtra(Params.LAST_CLASS, getClass().getSimpleName());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                    startActivity(intent);
                }
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
        if (illust.getUser().isIs_followed()) {
            baseBind.follow.setText("取消关注");
        } else {
            baseBind.follow.setText("+ 关注");
        }
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
        baseBind.userName.setText(illust.getUser().getName());
        baseBind.postTime.setText(illust.getCreate_date().substring(0, 16) + "投递");
        baseBind.totalView.setText(String.valueOf(illust.getTotal_view()));
        baseBind.totalLike.setText(String.valueOf(illust.getTotal_bookmarks()));
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
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(illust.getUser().getProfile_image_urls().getMedium()))
                .into(baseBind.userHead);
    }

    @Override
    public void vertical() {
        //竖屏
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
    }


    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return null;
    }
}
