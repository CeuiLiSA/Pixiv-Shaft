package ceui.lisa.dialogs;

import android.app.Dialog;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ToastUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.DialogFileNameBinding;
import ceui.lisa.utils.Local;

public class FileNameDialog extends BaseDialog<DialogFileNameBinding> {

    private IOnDismissListener onDismissListener;

    @Override
    void initLayout() {
        mLayoutID = R.layout.dialog_file_name;
    }

    @Override
    void initView(View v) {
        baseBind.fileNameType.setText(Shaft.sSettings.getFileNameType());
        baseBind.sure.setOnClickListener(view -> {
            if (TextUtils.isEmpty(baseBind.fileNameType.getText().toString())) {
                ToastUtils.showLong(R.string.shoud_not_be_empty);
            } else {
                Shaft.sSettings.setFileNameType(baseBind.fileNameType.getText().toString());
                Local.setSettings(Shaft.sSettings);
                dismiss();
            }
        });
        baseBind.cancel.setOnClickListener(view -> dismiss());
    }

    @Override
    void initData() {

    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (onDismissListener != null) {
            onDismissListener.OnDismiss(this);
        }
    }

    public FileNameDialog setOnDismissListener(IOnDismissListener onDismissListener) {
        this.onDismissListener = onDismissListener;
        return this;
    }

    public interface IOnDismissListener {
        void OnDismiss(FileNameDialog d);
    }
}