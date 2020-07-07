package ceui.lisa.fragments;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.blankj.utilcode.util.BarUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.ybq.android.spinkit.style.Wave;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.base.SwipeFragment;
import ceui.lisa.databinding.FragmentSlideBinding;
import ceui.lisa.dialogs.MuteDialog;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ShareIllust;

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
        baseBind.illustId.setText("作品ID：" + illust.getId());
        baseBind.userId.setText("画师ID：" + illust.getUser().getId());
        final BottomSheetBehavior<?> sheetBehavior = BottomSheetBehavior.from(baseBind.contentScrollView);

        baseBind.bottomBar.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                final int bottomCardHeight = baseBind.bottomBar.getHeight();
                final int deltaY = baseBind.coreLinear.getHeight() - baseBind.bottomBar.getHeight();
                sheetBehavior.setPeekHeight(bottomCardHeight, true);
                baseBind.headRela.setPadding(0, 0, 0, bottomCardHeight - DensityUtil.dp2px(16.0f));

                sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View bottomSheet, int newState) {

                    }

                    @Override
                    public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                        baseBind.headRela.setTranslationY(-deltaY * slideOffset * 0.7f);
                        Common.showLog(className + deltaY * slideOffset);
                    }
                });
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
        Glide.with(mContext)
                .load(GlideUtil.getOriginal(illust, 0))
                .transition(withCrossFade())
                .into(baseBind.headRela);
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
