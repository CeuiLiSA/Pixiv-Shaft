package ceui.lisa.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.blankj.utilcode.util.Utils;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringChain;
import com.hjq.toast.ToastUtils;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.tencent.mmkv.MMKV;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.http.CloudFlareDNSResponse;
import ceui.lisa.http.CloudFlareDNSService;
import ceui.lisa.models.UserContainer;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import retrofit2.Call;
import retrofit2.Callback;

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

    public static boolean isEmpty(List<?> list) {
        return list == null || list.size() == 0;
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

    public void showUriDialog(BaseActivity<?> activity) {

    }

    public static <T> void showLog(T t) {
        Log.d("==SHAFT== log ==> ", String.valueOf(t));
    }

    public static <T> void showToast(T t) {
        ToastUtils.show(t);
    }

    public static void showToast(int id) {
        ToastUtils.show(id);
    }

    //2成功， 3失败， 4info
    public static <T> void showToast(T t, int type) {
        ToastUtils.show(t);
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

    public static <T> void showToast(T t, boolean isLong) {
        ToastUtils.show(t);
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

    public static String checkEmpty(EditText before) {
        if (before != null && before.getText() != null && !TextUtils.isEmpty(before.getText().toString())) {
            return before.getText().toString();
        } else {
            return "";
        }
    }

    public static void animate(LinearLayout linearLayout) {
        SpringChain springChain = SpringChain.create(40, 8, 60, 10);

        int childCount = linearLayout.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View view = linearLayout.getChildAt(i);

            springChain.addSpring(new SimpleSpringListener() {
                @Override
                public void onSpringUpdate(Spring spring) {
                    view.setTranslationX((float) spring.getCurrentValue());
                }
            });
        }

        List<Spring> springs = springChain.getAllSprings();
        for (int i = 0; i < springs.size(); i++) {
            springs.get(i).setCurrentValue(400);
        }
        springChain.setControlSpringIndex(0).getControlSpring().setEndValue(0);
    }

    public static void createDialog(Context context){
        QMUIDialog qmuiDialog = new QMUIDialog.MessageDialogBuilder(context)
                .setTitle(context.getString(R.string.string_188))
                .setMessage(context.getString(R.string.dont_catch_me))
                .setSkinManager(QMUISkinManager.defaultInstance(context))
                .addAction(context.getString(R.string.string_189), new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        //保存SHOW_DIALOG 为false，不再提示
                        Shaft.getMMKV().encode(Params.SHOW_DIALOG, false);
                        dialog.dismiss();
                    }
                })
                .addAction(context.getString(R.string.string_190), new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        //保存SHOW_DIALOG 为true，需要继续提示
                        Shaft.getMMKV().encode(Params.SHOW_DIALOG, true);
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


    public static <T> String cutToJson(List<T> from) {
        if (isEmpty(from)) {
            return "";
        }

        if (from.size() > 5) {
            List<T> temp = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                temp.add(from.get(i));
            }
            return Shaft.sGson.toJson(temp);
        } else {
            return Shaft.sGson.toJson(from);
        }
    }

    public static boolean isAndroidQ() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public static void restart() {
        Intent intent = new Intent();
        String realActivityClassName = MainActivity.class.getName();
        intent.setComponent(new ComponentName(Utils.getApp(), realActivityClassName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Utils.getApp().startActivity(intent);
    }

    /**
     * left 0, right 5
     *
     * 结果只有 0 1 2 3 4
     *
     *
     * @param left
     * @param right
     * @return
     */
    public static int flatRandom(int left, int right) {
        Random r = new Random();
        return r.nextInt(right - left) + left;
    }

    public static int flatRandom(int right) {
        return flatRandom(0, right);
    }


    public static int resolveThemeAttribute(Context context, int resId){
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(resId, typedValue, true);
        return typedValue.data;
    }
}
