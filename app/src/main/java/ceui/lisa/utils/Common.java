package ceui.lisa.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.SearchRecentSuggestions;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class Common {




    public static void hideKeyboard(Activity activity) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        assert imm != null;
        if (imm.isActive() && activity.getCurrentFocus() != null) {
            if (activity.getCurrentFocus().getWindowToken() != null) {
                imm.hideSoftInputFromWindow(activity.getCurrentFocus().getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
            }
        }
    }

    public static String getRealFilePath(final Context context, final Uri uri) {
        if (null == uri) return null;
        final String scheme = uri.getScheme();
        String data = null;
        if (scheme == null)
            data = uri.getPath();
        else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            data = uri.getPath();
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.Images.ImageColumns.DATA}, null, null, null);
            if (null != cursor) {
                if (cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
                    if (index > -1) {
                        data = cursor.getString(index);
                    }
                }
                cursor.close();
            }
        }
        return data;
    }


    public static <T> void showLog(T t) {
        Log.d("a line of my log", String.valueOf(t));
    }

    private static Toast toast = null;



    public static <T> void showToast(T t) {
        if (toast == null) {
            toast = Toast.makeText(Shaft.getContext(), String.valueOf(t), Toast.LENGTH_SHORT);
        } else {
            toast.cancel();
            toast = Toast.makeText(Shaft.getContext(), String.valueOf(t), Toast.LENGTH_SHORT);
        }
        View view = LayoutInflater.from(Shaft.getContext()).inflate(R.layout.toast_item, null);
        TextView textView = view.findViewById(R.id.toast_text);
        textView.setText(String.valueOf(t));
        toast.setView(view);
        toast.show();
    }


    public static <T> void showToast(Context context, T t) {
        if (toast == null) {
            toast = Toast.makeText(context, String.valueOf(t), Toast.LENGTH_SHORT);
        } else {
            toast.setText(String.valueOf(t));
            toast.setDuration(Toast.LENGTH_SHORT);
        }
        toast.show();
    }

    public static void copy(Context context, String s){
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData mClipData = ClipData.newPlainText("Label", s);
        cm.setPrimaryClip(mClipData);
        showToast(s + "已复制到剪切板");
    }
}
