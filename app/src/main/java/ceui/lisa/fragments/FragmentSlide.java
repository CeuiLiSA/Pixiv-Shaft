package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.blankj.utilcode.util.BarUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.base.SwipeFragment;
import ceui.lisa.databinding.FragmentSlideBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;


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
        baseBind.bottomBar.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int bottomHeight = baseBind.bottomBar.getHeight();
                int[] location = new int[2];
                baseBind.bottomBar.getLocationOnScreen(location);
                int y = location[1];

                Common.showLog(className + "bottomHeight " + y);


                int headHeight = ScreenUtils.getScreenHeight();

                ViewGroup.LayoutParams params = baseBind.contentHead.getLayoutParams();
                params.height = headHeight;
                baseBind.contentHead.setLayoutParams(params);
                baseBind.bottomBar.getViewTreeObserver().removeOnGlobalLayoutListener(this);


//                baseBind.contentScrollView.scrollTo(0, baseBind.underScreen.getTop());
                Common.showLog(className + " Y" + baseBind.contentScrollView.getY());
                baseBind.contentScrollView.offsetTopAndBottom(-baseBind.bottomBar.getHeight());
                Common.showLog(className + " getTop" + baseBind.contentScrollView.getTop());
            }
        });
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return null;
    }
}
