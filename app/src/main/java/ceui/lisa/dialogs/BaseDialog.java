package ceui.lisa.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import ceui.lisa.model.UserModel;
import ceui.lisa.utils.Local;

public abstract class BaseDialog extends DialogFragment {

    protected Context mContext;
    protected Activity mActivity;
    protected int mLayoutID = -1;
    protected View parentView;
    protected UserModel mUserModel;
    protected Button sure, cancel;
    protected String className = this.getClass().getSimpleName() + " ";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = getContext();
        mActivity = getActivity();
        mUserModel = Local.getUser();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        if (parentView == null) {
            initLayout();
            parentView = LayoutInflater.from(mContext).inflate(mLayoutID, null);
            initView(parentView);
            initData();
        }
        builder.setView(parentView);
        return builder.create();
    }

    abstract void initLayout();

    abstract View initView(View v);

    abstract void initData();
}
