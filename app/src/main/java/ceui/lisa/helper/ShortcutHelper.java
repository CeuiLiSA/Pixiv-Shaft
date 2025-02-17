package ceui.lisa.helper;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.util.List;

import androidx.annotation.RequiresApi;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.IconCompat;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;

public class ShortcutHelper {

    public static void addAppShortcuts() {
        Context context = Shaft.getContext();
        String searchShortcutId = "search";

        List<ShortcutInfoCompat> shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context);
        if (shortcuts.stream().anyMatch(it -> it.getId().equals(searchShortcutId))) {
            return;
        }

        Intent intent = new Intent(context, TemplateActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索");
        IconCompat iconCompat;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                iconCompat = IconCompat.createWithBitmap(getAdaptiveBitmap(context, R.mipmap.ic_launcher_round));
            } catch (Exception e) {
                iconCompat = IconCompat.createWithResource(context, R.mipmap.ic_launcher_round);
            }
        } else {
            iconCompat = IconCompat.createWithResource(context, R.mipmap.ic_launcher_round);
        }
        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, searchShortcutId)
                .setShortLabel(context.getString(R.string.search))
                .setLongLabel(context.getString(R.string.search))
                .setIcon(iconCompat)
                .setIntent(intent)
                .build();

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static Bitmap getAdaptiveBitmap(Context context, int resId) {
        Drawable drawable = ResourcesCompat.getDrawable(context.getResources(), resId, null);
        if (drawable == null) {
            return null;
        }

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Bitmap maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(maskBitmap);

        Paint xferPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        xferPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        xferPaint.setColor(Color.RED);

        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);

        maskCanvas.drawRoundRect(0, 0, width, height, width / 2.0f, height / 2.0f, xferPaint);
        xferPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(maskBitmap, 0, 0, xferPaint);

        return bitmap;
    }
}
