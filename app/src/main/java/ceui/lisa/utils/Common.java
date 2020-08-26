package ceui.lisa.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.models.UserContainer;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

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

    public static void logOut(Context context) {
        if (Shaft.sUserModel != null) {
            Shaft.sUserModel.getResponse().getUser().setIs_login(false);
            Local.saveUser(Shaft.sUserModel);
            Intent intent = new Intent(context, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "登录注册");
            context.startActivity(intent);
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

    public static <T> void showToast(T t, View view) {
        showToast(t, view, QMUITipDialog.Builder.ICON_TYPE_SUCCESS);
    }

    //2成功， 3失败， 4info
    public static <T> void showToast(T t, View view, int type) {
        final QMUITipDialog tipDialog = new QMUITipDialog.Builder(view.getContext())
                .setIconType(type)
                .setTipWord(String.valueOf(t))
                .create();
        tipDialog.show();
        view.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(tipDialog.isShowing()) { //check if dialog is showing.

                    //get the Context object that was used to great the dialog
                    Context context = ((ContextWrapper)tipDialog.getContext()).getBaseContext();

                    //if the Context used here was an activity AND it hasn't been finished or destroyed
                    //then dismiss it
                    if(context instanceof Activity) {
                        if(!((Activity)context).isFinishing() && !((Activity)context).isDestroyed()) {
                            tipDialog.dismiss();
                        }
                    } else {
                        tipDialog.dismiss();
                    }
                }
            }
        }, 1000L);
    }

    public static String getAppVersionCode(Context context) {
        int versioncode = 0;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            // versionName = pi.versionName;
            versioncode = pi.versionCode;
        } catch (Exception e) {
            Log.e("VersionInfo", "Exception", e);
        }
        return versioncode + "";
    }

    /**
     * 返回当前程序版本名
     */
    public static String getAppVersionName(Context context) {
        String versionName=null;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            versionName = pi.versionName;
        } catch (Exception e) {
            Log.e("VersionInfo", "Exception", e);
        }
        return versionName;
    }

    public static void success(Context context, String s, View view) {
        QMUITipDialog dialog = new QMUITipDialog.Builder(context)
                .setIconType(QMUITipDialog.Builder.ICON_TYPE_SUCCESS)
                .setTipWord(s)
                .create();
        dialog.show();
        view.postDelayed(new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
            }
        }, 1500);
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

    public static void copy(Context context, String s) {
        copy(context, s, true);
    }

    public static void copy(Context context, String s, boolean hasHint) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData mClipData = ClipData.newPlainText("Label", s);
        cm.setPrimaryClip(mClipData);
        if (hasHint) {
            showToast(s + context.getString(R.string.has_copyed));
        }
    }

    public static String checkEmpty(String before) {
        return TextUtils.isEmpty(before) ? Shaft.getContext().getString(R.string.no_info) : before;
    }

    public static void createDialog(Context context){
        QMUIDialog qmuiDialog = new QMUIDialog.MessageDialogBuilder(context)
                .setTitle(context.getString(R.string.string_188))
                .setMessage(context.getString(R.string.dont_catch_me))
                .setSkinManager(QMUISkinManager.defaultInstance(context))
                .addAction(context.getString(R.string.string_189), new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        Local.setBoolean(Params.SHOW_DIALOG, false);
                        dialog.dismiss();
                    }
                })
                .addAction(context.getString(R.string.string_190), new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        Local.setBoolean(Params.SHOW_DIALOG, true);
                        dialog.dismiss();
                    }
                })
                .create();
        Window window = qmuiDialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(R.style.dialog_animation_scale);
        }
        qmuiDialog.show();
    }

    public static String getResponseBody(Response response) {

        Charset UTF8 = Charset.forName("UTF-8");
        ResponseBody responseBody = response.body();
        BufferedSource source = responseBody.source();
        try {
            source.request(Long.MAX_VALUE); // Buffer the entire body.
        } catch (IOException e) {
            e.printStackTrace();
        }
        Buffer buffer = source.buffer();

        Charset charset = UTF8;
        MediaType contentType = responseBody.contentType();
        if (contentType != null) {
            try {
                charset = contentType.charset(UTF8);
            } catch (UnsupportedCharsetException e) {
                e.printStackTrace();
            }
        }
        return buffer.clone().readString(charset);
    }

    public static void showUser(Context context, UserContainer userContainer) {
        Intent intent = new Intent(context, UserActivity.class);
        intent.putExtra(Params.USER_ID, userContainer.getUserId());
        context.startActivity(intent);
    }


}
