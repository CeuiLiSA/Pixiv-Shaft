package ceui.lisa.ui;

import android.view.animation.Interpolator;

import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import jp.wasabeef.recyclerview.animators.BaseItemAnimator;

public class LandingAnimatorNew extends BaseItemAnimator {

    public LandingAnimatorNew() {
    }

    public LandingAnimatorNew(Interpolator interpolator) {
        mInterpolator = interpolator;
    }

    @Override protected void animateRemoveImpl(final RecyclerView.ViewHolder holder) {
        ViewCompat.animate(holder.itemView)
                .alpha(0)
                .scaleX(1.5f)
                .scaleY(1.5f)
                .setDuration(getRemoveDuration())
                .setInterpolator(mInterpolator)
                .setListener(new DefaultRemoveVpaListener(holder))
                .setStartDelay(getRemoveDelay(holder))
                .start();
    }

    @Override protected void preAnimateAddImpl(RecyclerView.ViewHolder holder) {
        ViewCompat.setAlpha(holder.itemView, 0);
        ViewCompat.setTranslationY(holder.itemView, -50);
        ViewCompat.setTranslationZ(holder.itemView, -100);
        ViewCompat.setScaleX(holder.itemView, 0.5f);
        ViewCompat.setScaleY(holder.itemView, 0.5f);
    }

    @Override protected void animateAddImpl(final RecyclerView.ViewHolder holder) {
        ViewCompat.animate(holder.itemView)
                .alpha(1)
                .scaleX(1)
                .scaleY(1)
                .translationY(0.0f)
                .translationZ(0.0f)
                .setDuration(getAddDuration())
                .setInterpolator(mInterpolator)
                .setListener(new DefaultAddVpaListener(holder))
                .setStartDelay(getAddDelay(holder))
                .start();
    }
}
