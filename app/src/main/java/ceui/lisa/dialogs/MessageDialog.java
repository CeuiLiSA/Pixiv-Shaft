package ceui.lisa.dialogs;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.tencent.mmkv.MMKV;

import ceui.lisa.utils.Params;

public class MessageDialog {

    public static void showMessage(AppCompatActivity activity, String str) {
        new QMUIDialog.MessageDialogBuilder(activity)
                .setTitle("抱歉各位")
                .setSkinManager(QMUISkinManager.defaultInstance(activity))
                .setMessage(str)
                .setCanceledOnTouchOutside(false)
                .addAction("我知道了", new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        MMKV.defaultMMKV().encode(Params.SHOW_LONG_DIALOG, false);
                        dialog.dismiss();
                    }
                })
                .create()
                .show();
    }
}
