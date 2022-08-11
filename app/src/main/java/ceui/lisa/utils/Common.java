package ceui.lisa.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.Utils;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringChain;
import com.hjq.toast.ToastUtils;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.download.FileCreator;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.file.SAFile;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserContainer;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

public class Common {

    private static final String[][] safeReplacer = new String[][]{{"|", "%7c"}, {"\\", "%5c"}, {"?", "%3f"},
            {"*", "\u22c6"}, {"<", "%3c"}, {"\"", "%22"}, {":", "%3a"}, {">", "%3e"}, {"/", "%2f"}};

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

    public static void logOut(Context context, boolean deleteUser) {
        if (Shaft.sUserModel != null) {
            if (!Dev.isDev) { //测试状态，不要真的退出登录，只是跳转到登录页面
                Shaft.sUserModel.getUser().setIs_login(false);
                Local.saveUser(Shaft.sUserModel);
                if(deleteUser){
                    UserEntity userEntity = new UserEntity();
                    userEntity.setUserID(Shaft.sUserModel.getUserId());
                    AppDatabase.getAppDatabase(context)
                            .downloadDao().deleteUser(userEntity);
                }
            }
            Intent intent = new Intent(context, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "登录注册");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        }
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
        ClipBoardUtils.putTextIntoClipboard(context, s, true);
    }

    public static void copy(Context context, String s, boolean hasHint) {
        ClipBoardUtils.putTextIntoClipboard(context, s, hasHint);
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

        Charset UTF8 = StandardCharsets.UTF_8;
        ResponseBody responseBody = response.body();
        BufferedSource source = responseBody.source();
        try {
            source.request(Long.MAX_VALUE); // Buffer the entire body.
        } catch (IOException e) {
            e.printStackTrace();
        }
        Buffer buffer = source.getBuffer();

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

    /**
     * 解析主题相关的 attribute 的当前值
     */
    public static int resolveThemeAttribute(Context context, int resId){
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(resId, typedValue, true);
        return typedValue.data;
    }

    /**
     * 移除文件系统保留字符
     */
    public static String removeFSReservedChars(String s){
        try {
            for (String[] strings : safeReplacer) {
                s = s.replace(strings[0], strings[1]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return s;
    }

    /**
     * 检查插画是否已经下载过
     * */
    public static boolean isIllustDownloaded(IllustsBean illust) {
        try {
            if (illust.getPage_count() == 1) {
                if (Shaft.sSettings.getDownloadWay() == 1) {
                    return SAFile.isFileExists(Shaft.getContext(), illust);
                } else {
                    return FileCreator.isExist(illust, 0);
                }
            } else {
                IntStream pageIndexStream = IntStream.range(0, illust.getPage_count() - 1);
                if (Shaft.sSettings.getDownloadWay() == 1) {
                    return pageIndexStream
                            .allMatch(index -> SAFile.isFileExists(Shaft.getContext(), illust, index));
                } else {
                    return pageIndexStream
                            .allMatch(index -> FileCreator.isExist(illust, index));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 检查插画某页是否已经下载过
     * */
    public static boolean isIllustDownloaded(IllustsBean illust, int index) {
        try {
            if (illust.getPage_count() == 1) {
                if (Shaft.sSettings.getDownloadWay() == 1) {
                    return SAFile.isFileExists(Shaft.getContext(), illust);
                } else {
                    return FileCreator.isExist(illust, 0);
                }
            } else {
                if (Shaft.sSettings.getDownloadWay() == 1) {
                    return SAFile.isFileExists(Shaft.getContext(), illust, index);
                } else {
                    return FileCreator.isExist(illust, index);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 根据ISO8601格式的时间字符串获取年月日时分格式的字符串
     */
    public static String getLocalYYYYMMDDHHMMString(String source) {
        try {
            return ZonedDateTime.parse(source).withZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            e.printStackTrace();
            return source.substring(0, 16);
        }
    }

    /**
     * 根据ISO8601格式的时间字符串获取年月日时分秒格式的字符串
     */
    public static String getLocalYYYYMMDDHHMMSSString(String source) {
        try {
            return ZonedDateTime.parse(source).withZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            e.printStackTrace();
            return source;
        }
    }

    /**
     * 根据ISO8601格式的时间字符串获取年月日时分秒格式的字符串（文件用）
     */
    public static String getLocalYYYYMMDDHHMMSSFileString(String source) {
        try {
            return ZonedDateTime.parse(source).withZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        } catch (Exception e) {
            e.printStackTrace();
            return source;
        }
    }

    /**
     * 获取小说文字颜色配置
     */
    public static int getNovelTextColor(){
        int color = Shaft.sSettings.getNovelHolderTextColor();
        if(color == 0){
            return ContextCompat.getColor(Shaft.getContext(), R.color.white);
        }
        return color;
    }

    /**
     * 文件大小是否满足反向搜索条件
     *
     * @param uri 文件地址
     * @return 大小是否可搜索
     */
    public static boolean isFileSizeOkToReverseSearch(Uri uri, long maxImageSize) {
        Cursor cursor = Shaft.getContext().getContentResolver().query(uri, null, null, null, null);
        if (cursor == null) {
            return false;
        }
        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
        cursor.moveToFirst();
        boolean ret = cursor.getLong(sizeIndex) <= maxImageSize;
        cursor.close();
        return ret;
    }

    /**
     *  复制资源Uri到内部缓存，from com.blankj.utilcode.util.UriUtils
     * @param uri
     * @return cached file
     */
    public static File copyUriToImageCacheFolder(Uri uri) {
        InputStream is = null;
        try {
            is = Utils.getApp().getContentResolver().openInputStream(uri);
            File file = new File(LegacyFile.imageCacheFolder(Utils.getApp()), String.valueOf(System.currentTimeMillis()));
            FileIOUtils.writeFileFromIS(file.getAbsolutePath(), is);
            return file;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static Uri copyBitmapToImageCacheFolder(Bitmap bitmap, String fileName){
        try {
            // shared_images
            File cachePath = new File(Utils.getApp().getExternalCacheDir(), "images");
            cachePath.mkdirs();
            File file = new File(cachePath, fileName);
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream);
            fileOutputStream.close();
            return FileProvider.getUriForFile(Utils.getApp(), "ceui.lisa.pixiv.provider", file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
