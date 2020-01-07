package ceui.lisa.dialogs;

import android.view.View;

import ceui.lisa.R;
import ceui.lisa.databinding.DialogAvoid251Binding;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;

public class Avoid251Dialog extends BaseDialog<DialogAvoid251Binding> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.dialog_avoid_251;
    }

    @Override
    View initView(View v) {
        baseBind.sure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Local.setBoolean(Params.SHOW_DIALOG, !baseBind.checkbox.isChecked());
                dismiss();
            }
        });
        return v;
    }

    @Override
    void initData() {

    }
}
