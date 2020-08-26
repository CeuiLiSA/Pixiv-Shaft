package ceui.lisa.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.DialogFragment;

public abstract class BaseDialog<Layout extends ViewDataBinding> extends DialogFragment {

    protected Context mContext;
    protected Activity mActivity;
    protected Layout baseBind;
    protected int mLayoutID = -1;
    protected View parentView;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        initLayout();
        baseBind = DataBindingUtil.inflate(inflater, mLayoutID, container, false);
        if (baseBind != null) {
            parentView = baseBind.getRoot();
        } else {
            parentView = inflater.inflate(mLayoutID, container, false);
        }
        return parentView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        initView(view);
        initData();
    }

    @Override
    public void onResume() {
        super.onResume();
        Dialog dialog = getDialog();
        if(dialog != null){
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = getResources().getDisplayMetrics().widthPixels * 6 / 7; //设置宽度
                window.setAttributes(lp);
//                window.setWindowAnimations(R.style.dialog_animation_scale);
//                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));//设置Dialog背景透明
            }
        }
    }

    abstract void initLayout();

    abstract void initView(View v);

    abstract void initData();
}
