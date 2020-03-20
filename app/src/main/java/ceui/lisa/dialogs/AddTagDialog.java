package ceui.lisa.dialogs;

import android.text.TextUtils;
import android.view.View;


import ceui.lisa.R;
import ceui.lisa.databinding.DialogAddTagBinding;
import ceui.lisa.fragments.FragmentSB;
import ceui.lisa.utils.Common;

public class AddTagDialog extends BaseDialog<DialogAddTagBinding> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.dialog_add_tag;
    }

    @Override
    void initView(View v) {
        sure = v.findViewById(R.id.sure);
        sure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(baseBind.tagName.getText().toString())) {
                    Common.showToast("请输入标签名");
                    return;
                }

                if (getParentFragment() instanceof FragmentSB) {
                    ((FragmentSB) getParentFragment()).addTag(baseBind.tagName.getText().toString());
                }

                dismiss();
            }
        });
        cancel = v.findViewById(R.id.cancel);
        cancel.setOnClickListener(view -> dismiss());
    }

    @Override
    void initData() {

    }
}
