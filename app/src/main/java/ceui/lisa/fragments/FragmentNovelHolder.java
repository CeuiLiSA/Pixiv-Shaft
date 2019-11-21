package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentNovelHolderBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.NovelDetail;
import ceui.lisa.utils.Params;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentNovelHolder extends BaseBindFragment<FragmentNovelHolderBinding> {

    private int novelID;

    public static FragmentNovelHolder newInstance(int pID) {
        Bundle args = new Bundle();
        args.putInt(Params.NOVEL_ID, pID);
        FragmentNovelHolder fragment = new FragmentNovelHolder();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        novelID = bundle.getInt(Params.NOVEL_ID);
    }

    @Override
    public void initView(View view) {
        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_novel_holder;
    }

    @Override
    void initData() {
//        if(novelID >= 1000) {
//
//        } else {
//            novelID = 10900170;
//        }
        Retro.getAppApi().getNovelDetail(Shaft.sUserModel.getResponse().getAccess_token(), novelID)
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
                        }
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.progressRela.setVisibility(View.INVISIBLE);
                    }
                });
    }
}
