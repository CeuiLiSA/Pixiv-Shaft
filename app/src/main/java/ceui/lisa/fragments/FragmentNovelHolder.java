package ceui.lisa.fragments;

import android.animation.Animator;
import android.os.Bundle;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.Glide;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentNovelHolderBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.NovelBean;
import ceui.lisa.model.NovelDetail;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.AnimeListener;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentNovelHolder extends BaseBindFragment<FragmentNovelHolderBinding> {

    private boolean isOpen = false;
    private NovelBean mNovelBean;

    public static FragmentNovelHolder newInstance(int index) {
        Bundle args = new Bundle();
        args.putInt(Params.INDEX, index);
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
        int index = bundle.getInt(Params.INDEX);
        mNovelBean = DataChannel.get().getNovelList().get(index);
    }

    @Override
    public void initView(View view) {
        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
        BarUtils.setStatusBarColor(mActivity, getResources().getColor(R.color.hito_bg));
        baseBind.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(isOpen){
                    isOpen = false;
                    close();
                }else {
                    isOpen = true;
                    open();
                }
            }
        });
    }

    @Override
    void initData() {
//        if(novelID >= 1000) {
//
//        } else {
//            novelID = 10900170;
//        }
        Retro.getAppApi().getNovelDetail(Shaft.sUserModel.getResponse().getAccess_token(), mNovelBean.getId())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<NovelDetail>() {
                    @Override
                    public void success(NovelDetail novelDetail) {
                        if (novelDetail.getNovel_text().contains("[newpage]")) {
                            String[] partList = novelDetail.getNovel_text().split("\\[newpage]");
                            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                                @NonNull
                                @Override
                                public Fragment getItem(int position) {
                                    return FragmentSingleNovel.newInstance(partList[position]);
                                }

                                @Override
                                public int getCount() {
                                    return partList.length;
                                }
                            });
                        }else {
                            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                                @NonNull
                                @Override
                                public Fragment getItem(int position) {
                                    return FragmentSingleNovel.newInstance(novelDetail.getNovel_text());
                                }

                                @Override
                                public int getCount() {
                                    return 1;
                                }
                            });
                        }
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.progressRela.setVisibility(View.INVISIBLE);
                    }
                });
    }

    private void open(){
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

    private void close(){
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
