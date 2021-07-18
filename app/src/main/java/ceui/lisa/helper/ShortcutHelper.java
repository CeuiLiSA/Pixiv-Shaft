package ceui.lisa.helper;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;

public class ShortcutHelper {

    public static void addAppShortcuts(){
        Context context = Shaft.getContext();
        Intent intent = new Intent(context, TemplateActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索");
        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, "search")
                .setShortLabel(context.getString(R.string.search))
                .setLongLabel(context.getString(R.string.search))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.logo_final_round))
                .setIntent(intent)
                .build();

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
    }
}
