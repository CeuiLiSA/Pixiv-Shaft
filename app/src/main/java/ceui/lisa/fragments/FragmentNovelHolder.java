package ceui.lisa.fragments;

import android.animation.Animator;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewAnimationUtils;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.Glide;

import java.util.Arrays;
import java.util.Collections;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.VAdapter;
import ceui.lisa.databinding.FragmentNovelHolderBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.AnimeListener;
import ceui.lisa.view.ScrollChange;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentNovelHolder extends BaseBindFragment<FragmentNovelHolderBinding> {

    private boolean isOpen = false;
    private NovelBean mNovelBean;

    public static FragmentNovelHolder newInstance(NovelBean novelBean) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, novelBean);
        FragmentNovelHolder fragment = new FragmentNovelHolder();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_novel_holder;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mNovelBean = (NovelBean) bundle.getSerializable(Params.CONTENT);
        PixivOperate.insertNovelViewHistory(mNovelBean);
    }

    @Override
    public void initView(View view) {
        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
        baseBind.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isOpen) {
                    isOpen = false;
                    close();
                } else {
                    isOpen = true;
                    open();
                }
            }
        });
        if (mNovelBean.isIs_bookmarked()) {
            baseBind.like.setText("取消收藏");
        } else {
            baseBind.like.setText("收藏");
        }
        baseBind.like.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PixivOperate.postLikeNovel(mNovelBean, Shaft.sUserModel,
                        FragmentLikeIllust.TYPE_PUBLUC, baseBind.like);
            }
        });
        baseBind.like.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mNovelBean.isIs_bookmarked()) {

                } else {
                    PixivOperate.postLikeNovel(mNovelBean, Shaft.sUserModel,
                            FragmentLikeIllust.TYPE_PRIVATE, baseBind.like);
                }
                return true;
            }
        });
        View.OnClickListener seeUser = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showUser(mContext, mNovelBean.getUser());
            }
        };
        baseBind.userHead.setOnClickListener(seeUser);
        baseBind.userName.setOnClickListener(seeUser);
        baseBind.userName.setText(mNovelBean.getUser().getName());
        baseBind.viewPager.setLayoutManager(new ScrollChange(mContext));
        baseBind.viewPager.setHasFixedSize(false);
        baseBind.novelTitle.setText("标题：" + mNovelBean.getTitle());
        if (mNovelBean.getSeries() != null && !TextUtils.isEmpty(mNovelBean.getSeries().getTitle())) {
            baseBind.novelSeries.setText("系列：" + mNovelBean.getSeries().getTitle());
        }
        Glide.with(mContext).load(GlideUtil.getHead(mNovelBean.getUser())).into(baseBind.userHead);
    }

    @Override
    void initData() {
        if (Dev.isDev) {
            mNovelBean.setId(10900170);
        }
        Retro.getAppApi().getNovelDetail(Shaft.sUserModel.getResponse().getAccess_token(), mNovelBean.getId())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<NovelDetail>() {
                    @Override
                    public void success(NovelDetail novelDetail) {
                        if (novelDetail.getNovel_text().contains("[newpage]")) {
                            String[] partList = novelDetail.getNovel_text().split("\\[newpage]");
                            baseBind.viewPager.setAdapter(new VAdapter(
                                    Arrays.asList(partList), mContext));
                        } else {
                            baseBind.viewPager.setAdapter(new VAdapter(
                                    Collections.singletonList(novelDetail.getNovel_text()), mContext));
                        }
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.progressRela.setVisibility(View.INVISIBLE);
                    }
                });
    }

    private void open() {
        ((ScrollChange) baseBind.viewPager.getLayoutManager()).setScrollEnabled(false);
        int centerX = baseBind.awesomeCard.getRight();
        int centerY = baseBind.awesomeCard.getBottom();
        float finalRadius = (float) Math.hypot((double) centerX, (double) centerY);
        Animator mCircularReveal = ViewAnimationUtils.createCircularReveal(
                baseBind.awesomeCard, centerX, centerY, 0, finalRadius);
        mCircularReveal.setDuration(600);
        mCircularReveal.addListener(new AnimeListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                baseBind.fab.setImageResource(R.drawable.ic_close_black_24dp);
                baseBind.awesomeCard.setVisibility(View.VISIBLE);
            }
        });
        mCircularReveal.start();
    }

    private void close() {
        ((ScrollChange) baseBind.viewPager.getLayoutManager()).setScrollEnabled(true);
        int centerX = baseBind.awesomeCard.getRight();
        int centerY = baseBind.awesomeCard.getBottom();
        float finalRadius = (float) Math.hypot((double) centerX, (double) centerY);
        Animator mCircularReveal = ViewAnimationUtils.createCircularReveal(
                baseBind.awesomeCard, centerX, centerY, finalRadius, 0);
        mCircularReveal.setDuration(600);
        mCircularReveal.addListener(new AnimeListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                baseBind.awesomeCard.setVisibility(View.INVISIBLE);
                baseBind.fab.setImageResource(R.drawable.ic_keyboard_arrow_up_black_24dp);
            }
        });
        mCircularReveal.start();
    }
}
