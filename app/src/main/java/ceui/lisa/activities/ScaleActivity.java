package ceui.lisa.activities;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringListener;
import com.facebook.rebound.SpringSystem;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import ceui.lisa.R;
import ceui.lisa.base.BaseActivity;
import ceui.lisa.databinding.ActicityScaleBinding;
import ceui.lisa.utils.Common;

public class ScaleActivity extends BaseActivity<ActicityScaleBinding> {

    @Override
    protected int initLayout() {
        return R.layout.acticity_scale;
    }

    SpringSystem mSpringSystem = SpringSystem.create();
    Spring mSpringX = mSpringSystem.createSpring();
    Spring mSpringY = mSpringSystem.createSpring();
    BottomSheetBehavior<?> bottomSheetBehavior;

    @Override
    protected void initView() {
        mSpringX.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(80, 15));
        mSpringY.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(80, 15));

        mSpringX.addListener(new SpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                baseBind.relaParent.setScaleX((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }

            @Override
            public void onSpringActivate(Spring spring) {

            }

            @Override
            public void onSpringEndStateChange(Spring spring) {

            }
        });
        mSpringY.addListener(new SpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                baseBind.relaParent.setScaleY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }

            @Override
            public void onSpringActivate(Spring spring) {

            }

            @Override
            public void onSpringEndStateChange(Spring spring) {

            }
        });
        baseBind.showAnimate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                down();
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        baseBind.closeAnimate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                up();
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
            }
        });

        baseBind.designBottomSheet1.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                Common.showLog(className + baseBind.designBottomSheet1.getHeight());
                ViewGroup.LayoutParams layoutParams = baseBind.designBottomSheet1.getLayoutParams();
                layoutParams.height = (int) (baseBind.designBottomSheet1.getHeight() * 0.93f);
                baseBind.designBottomSheet1.setLayoutParams(layoutParams);
                baseBind.designBottomSheet1.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
        bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.design_bottom_sheet1));
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {

                Common.showLog(className + newState);
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                Common.showLog(className + slideOffset);
                baseBind.cover.setAlpha(slideOffset);
                baseBind.relaParent.setScaleX(1 - slideOffset * X_REST);
                baseBind.relaParent.setScaleY(1 - slideOffset * Y_REST);
            }
        });
    }

    private static final float X_REST = 0.1f;
    private static final float Y_REST = 0.07f;

    private void down() {
        if (mSpringX.getCurrentValue() == 0.0f) {
            mSpringX.setCurrentValue(1.0f);
        }
        if (mSpringY.getCurrentValue() == 0.0f) {
            mSpringY.setCurrentValue(1.0f);
        }
        mSpringX.setEndValue(0.90f);
        mSpringY.setEndValue(0.93f);
    }

    private void up() {
        mSpringX.setEndValue(1.0f);
        mSpringY.setEndValue(1.0f);
    }

    @Override
    protected void initData() {

    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }

//    public static class TopFragment extends BottomSheetDialogFragment {
//        @Nullable
//        @Override
//        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
//            return inflater.inflate(R.layout.test,container,false);
//        }
//    }
}
