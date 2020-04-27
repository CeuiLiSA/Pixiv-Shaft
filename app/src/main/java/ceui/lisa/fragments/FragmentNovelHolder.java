package ceui.lisa.fragments;

import android.animation.Animator;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.Glide;
import com.skydoves.transformationlayout.OnTransformFinishListener;

import java.util.Arrays;
import java.util.Collections;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.VAdapter;
import ceui.lisa.databinding.FragmentNovelHolderBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.AnimeListener;
import ceui.lisa.view.ScrollChange;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentNovelHolder extends BaseFragment<FragmentNovelHolderBinding> {

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
    public void initLayout() {
        mLayoutID = R.layout.fragment_novel_holder;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mNovelBean = (NovelBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public void initView(View view) {
        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
        baseBind.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.transformationLayout.startTransform();
            }
        });
        baseBind.transformationLayout.onTransformFinishListener = new OnTransformFinishListener() {
            @Override
            public void onFinish(boolean isTransformed) {
                Common.showLog(className + isTransformed);
                isOpen = isTransformed;
            }
        };
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
            baseBind.novelSeries.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, mNovelBean);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列作品");
                    startActivity(intent);
                }
            });
        }
        Glide.with(mContext).load(GlideUtil.getHead(mNovelBean.getUser())).into(baseBind.userHead);
    }

    @Override
    void initData() {
        getNovel(mNovelBean);
    }

    private void getNovel(NovelBean novelBean) {
        PixivOperate.insertNovelViewHistory(novelBean);
        baseBind.viewPager.setVisibility(View.INVISIBLE);
        baseBind.progressRela.setVisibility(View.VISIBLE);
        Retro.getAppApi().getNovelDetail(Shaft.sUserModel.getResponse().getAccess_token(), novelBean.getId())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<NovelDetail>() {
                    @Override
                    public void success(NovelDetail novelDetail) {
                        baseBind.viewPager.setVisibility(View.VISIBLE);
                        baseBind.viewPager.setOnTouchListener(new View.OnTouchListener() {
                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                if (isOpen) {
                                    Common.showLog(className + "关闭card");
                                    baseBind.transformationLayout.finishTransform();
                                    isOpen = false;
                                }
                                return false;
                            }
                        });
                        if (novelDetail.getNovel_text().contains("[newpage]")) {
                            String[] partList = novelDetail.getNovel_text().split("\\[newpage]");
                            baseBind.viewPager.setAdapter(new VAdapter(
                                    Arrays.asList(partList), mContext));
                        } else {
                            baseBind.viewPager.setAdapter(new VAdapter(
                                    Collections.singletonList(novelDetail.getNovel_text()), mContext));
                        }
                        if (novelDetail.getSeries_prev() != null && novelDetail.getSeries_prev().getId() != 0) {
                            baseBind.showPrev.setVisibility(View.VISIBLE);
                            baseBind.showPrev.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    baseBind.transformationLayout.finishTransform();
                                    getNovel(novelDetail.getSeries_prev());
                                }
                            });
                        } else {
                            baseBind.showPrev.setVisibility(View.INVISIBLE);
                        }
                        if (novelDetail.getSeries_next() != null && novelDetail.getSeries_next().getId() != 0) {
                            baseBind.showNext.setVisibility(View.VISIBLE);
                            baseBind.showNext.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    baseBind.transformationLayout.finishTransform();
                                    getNovel(novelDetail.getSeries_next());
                                }
                            });
                        } else {
                            baseBind.showNext.setVisibility(View.INVISIBLE);
                        }
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.progressRela.setVisibility(View.INVISIBLE);
                    }
                });
    }
}
