package ceui.lisa.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * Origin:https://github.com/RikkaW/SearchByImage.git
 * <p>
 * Created by Rikka on 2015/12/18.
 */
public class ClipBoardUtils {
    public static void putTextIntoClipboard(Context context, String text) {
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clipData = ClipData.newPlainText("copy text", text);
        clipboardManager.setPrimaryClip(clipData);
        Common.showToast(text + "已复制到剪贴板");
    }
}
