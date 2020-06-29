package ceui.lisa.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.DialogFragment;

import ceui.lisa.R;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Local;

public abstract class BaseDialog<Layout extends ViewDataBinding> extends DialogFragment {

    protected Context mContext;
    protected Activity mActivity;
    protected Layout baseBind;
    protected int mLayoutID = -1;
    protected View parentView;
    protected Button sure, cancel;
    protected String className = this.getClass().getSimpleName() + " ";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = requireContext();
        mActivity = requireActivity();

        Bundle bundle = getArguments();
        if (bundle != null) {
            initBundle(bundle);
        }
    }

    public void initBundle(Bundle bundle) {

    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        if (parentView == null) {
            initLayout();
            parentView = LayoutInflater.from(mContext).inflate(mLayoutID, null);
            baseBind = DataBindingUtil.bind(parentView);
            initView(parentView);
            initData();
        }
        builder.setView(parentView);
        return builder.create();
    }

    @Override
    public void onResume() {
        super.onResume();
        Dialog dialog = getDialog();
        if(dialog != null){
            Window window = dialog.getWindow();
            if (window != null) {
                window.setWindowAnimations(R.style.dialog_animation_scale);
            }
        }
    }

    abstract void initLayout();

    abstract void initView(View v);

    abstract void initData();
}
