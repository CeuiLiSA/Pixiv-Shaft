package ceui.lisa.key;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.View;

public class XHAnim {


    private View view;

    private Context context;
    private ObjectAnimator scaleX;
    private int time;

    public XHAnim(Context context) {
        this.context = context;
    }

    public XHAnim setView(View view) {

        this.view = view;
        return this;
    }

    /**
     * @param startAnimDP
     * @param endAnimDP
     * @param time
     */

    public XHAnim setChangeWidthAnim(float startAnimDP, float endAnimDP, int time) {

        int startAnim = dp2Px(startAnimDP);
        int endAnim = dp2Px(endAnimDP);

        this.time = time;
        ViewAnimFactory viewAnimFactory = new ViewAnimFactory();
        viewAnimFactory.setView(view);
        scaleX = ObjectAnimator.ofInt(viewAnimFactory, "width", startAnim, endAnim);
        return this;

    }

    /**
     * @param startAnimDP
     * @param endAnimDP
     * @param time
     * @return
     */

    public XHAnim setChangeHeightAnim(float startAnimDP, float endAnimDP, int time) {
        int startAnim = (int) startAnimDP;
        int endAnim = (int) endAnimDP;
        this.time = time;
        ViewAnimFactory viewAnimFactory = new ViewAnimFactory();
        viewAnimFactory.setView(view);
        scaleX = ObjectAnimator.ofInt(viewAnimFactory, "height", startAnim, endAnim);


        return this;
    }


    public void start() {

        scaleX.setDuration(time);
        scaleX.start();

    }


    private int dp2Px(float dp) {
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

}
