package ceui.lisa.fragments;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSingleNovelBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.NovelDetail;
import ceui.lisa.utils.Params;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentSingleNovel extends BaseBindFragment<FragmentSingleNovelBinding> {


    private String novelString;

    public static FragmentSingleNovel newInstance(String novel) {

        Bundle args = new Bundle();
        args.putString(Params.CONTENT, novel);
        FragmentSingleNovel fragment = new FragmentSingleNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_single_novel;
    }

    @Override
    public void initBundle(Bundle bundle) {
        novelString = bundle.getString(Params.CONTENT);
    }

    @Override
    public void initView(View view) {
        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
    }

    @Override
    void initData() {
        baseBind.novelDetail.setText("\n" + novelString);
    }
}
