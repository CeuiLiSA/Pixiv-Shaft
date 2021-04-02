package ceui.lisa.helper;

import com.blankj.utilcode.util.ScreenUtils;

import java.lang.reflect.Field;

import androidx.annotation.NonNull;
import androidx.customview.widget.ViewDragHelper;
import androidx.drawerlayout.widget.DrawerLayout;

public class DrawerLayoutHelper {
    public static void setCustomLeftEdgeSize(@NonNull DrawerLayout drawerLayout, float displayWidthPercentage) {
        try {
            // find ViewDragHelper and set it accessible
            Field leftDraggerField = drawerLayout.getClass().getDeclaredField("mLeftDragger");
            if (leftDraggerField == null) {
                return;
            }
            leftDraggerField.setAccessible(true);
            ViewDragHelper leftDragger = (ViewDragHelper) leftDraggerField.get(drawerLayout);
            // find edgesize and set is accessible
            Field edgeSizeField = leftDragger.getClass().getDeclaredField("mEdgeSize");
            edgeSizeField.setAccessible(true);
            int edgeSize = edgeSizeField.getInt(leftDragger);
            // set new edgesize
            int widthPixels = ScreenUtils.getScreenWidth();
            edgeSizeField.setInt(leftDragger, Math.max(edgeSize, (int) (widthPixels * displayWidthPercentage)));

            //获取 Layout 的 ViewDragCallBack 实例mLeftCallback
            //更改其属性 mPeekRunnable
            Field leftCallbackField = drawerLayout.getClass().getDeclaredField("mLeftCallback");
            leftCallbackField.setAccessible(true);

            //因为无法直接访问私有内部类，所以该私有内部类实现的接口非常重要，通过多态的方式获取实例
            ViewDragHelper.Callback leftCallback = (ViewDragHelper.Callback)leftCallbackField.get(drawerLayout);

            Field peekRunnableField = leftCallback.getClass().getDeclaredField("mPeekRunnable");
            peekRunnableField.setAccessible(true);
            peekRunnableField.set(leftCallback, new Runnable(){
                @Override
                public void run() {
                }
            });

        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }
}
