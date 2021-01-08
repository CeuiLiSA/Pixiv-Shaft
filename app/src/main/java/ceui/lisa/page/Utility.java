package ceui.lisa.page;


import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import ceui.lisa.activities.Shaft;


public class Utility {

    private static Rect seekRect;

    public static int px2sp(float pxValue) {
        final float fontScale = Shaft.getContext().getResources().getDisplayMetrics().scaledDensity;
        return (int) (pxValue / fontScale + 0.5f);
    }

    public static int sp2px(float spValue) {
        final float fontScale = Shaft.getContext().getResources().getDisplayMetrics().scaledDensity;
        return (int) (spValue * fontScale + 0.5f);
    }

    public static int dip2px(float dipValue) {
        final float scale = Shaft.getContext().getResources().getDisplayMetrics().density;
        return (int) (dipValue * scale + 0.5f);
    }

    public static int px2dip(float pxValue) {
        final float scale = Shaft.getContext().getResources().getDisplayMetrics().density;
        return (int) (pxValue / scale + 0.5f);
    }

    /**
     * 增加seekBar可点击区域
     *
     * @param sb
     */
    public static void addSeekBarTouchPoint(SeekBar sb) {
        if (sb == null)
            return;

        try {
            ViewGroup vp = (ViewGroup) sb.getParent();
            vp.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    seekRect = new Rect();
                    sb.getHitRect(seekRect);

                    if ((event.getY() >= (seekRect.top - 50)) && (event.getY() <= (seekRect.bottom + 50))) {

                        float y = seekRect.top + seekRect.height() / 2;
                        //seekBar only accept relative x
                        float x = event.getX() - seekRect.left;
                        if (x < 0) {
                            x = 0;
                        } else if (x > seekRect.width()) {
                            x = seekRect.width();
                        }
                        MotionEvent me = MotionEvent.obtain(event.getDownTime(), event.getEventTime(),
                                event.getAction(), x, y, event.getMetaState());
                        return sb.onTouchEvent(me);

                    }
                    return false;
                }
            });
        } catch (Exception ex) {
        }
    }

    public static void openPreviewBook(Context context, int bookId) {

    }
}