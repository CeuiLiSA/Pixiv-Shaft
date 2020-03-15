package ceui.lisa.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.qmuiteam.qmui.widget.dialog.QMUITipDialog;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.model.MenuItem;
import ceui.lisa.models.UserBean;
import ceui.lisa.models.UserContainer;
import ceui.lisa.models.UserModel;
import ceui.lisa.models.UserPreviewsBean;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

import static ceui.lisa.fragments.FragmentFilter.FILE_NAME;

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

    public static void showUser(Context context, int userID) {

    }

    public static void showUser(Context context, UserContainer userContainer) {
        Intent intent = new Intent(context, UActivity.class);
        intent.putExtra(Params.USER_ID, userContainer.getUserId());
        context.startActivity(intent);
    }

    public static int getFileNameType() {
        String currentType = Shaft.sSettings.getFileNameType();
        int index = 0;
        for (int i = 0; i < FILE_NAME.length; i++) {
            if (FILE_NAME[i].equals(currentType)) {
                index = i;
                break;
            }
        }
        return index;
    }

    public static List<MenuItem> getMenuList() {
        List<MenuItem> itemList = new ArrayList<>();
        itemList.add(new MenuItem("漫画", 0));
        itemList.add(new MenuItem("小说", 0));
        itemList.add(new MenuItem("最新", 0));
        itemList.add(new MenuItem("特辑", 0));
        itemList.add(new MenuItem("画廊", 0));
        itemList.add(new MenuItem("一言", 0));
        itemList.add(new MenuItem("以图搜源", 0));
        return itemList;
    }
}
