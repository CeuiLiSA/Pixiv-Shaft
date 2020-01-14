package ceui.lisa.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class Common {

    private static Toast toast = null;

    public static boolean isNumeric(String str) {
        for (int i = str.length(); --i >= 0; ) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

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
            Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{MediaStore.Images.ImageColumns.DATA}, null, null, null);
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
        Log.d("==SHAFT== log ==> ", String.valueOf(t));
    }

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

    public static <T> void showToast(T t, boolean isLong) {
        if (toast == null) {
            toast = Toast.makeText(Shaft.getContext(), String.valueOf(t), isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        } else {
            toast.cancel();
            toast = Toast.makeText(Shaft.getContext(), String.valueOf(t), isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
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

    public static void copy(Context context, String s) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData mClipData = ClipData.newPlainText("Label", s);
        cm.setPrimaryClip(mClipData);
        showToast(s + context.getString(R.string.has_copyed));
    }

    public static String checkEmpty(String before) {
        return TextUtils.isEmpty(before) ? Shaft.getContext().getString(R.string.no_info) : before;
    }

    public static void createDialog(Context context){
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("欢迎使用！");
        builder.setMessage(context.getString(R.string.dont_catch_me));
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Local.setBoolean(Params.SHOW_DIALOG, true);
            }
        });
        builder.setNegativeButton("确定且不再提示", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Local.setBoolean(Params.SHOW_DIALOG, false);
            }
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(context.getResources().getColor(R.color.colorPrimary));
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(context.getResources().getColor(R.color.colorPrimary));
    }
}
