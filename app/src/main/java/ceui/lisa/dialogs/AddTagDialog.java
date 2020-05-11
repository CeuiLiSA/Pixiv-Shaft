package ceui.lisa.dialogs;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;


import ceui.lisa.R;
import ceui.lisa.databinding.DialogAddTagBinding;
import ceui.lisa.fragments.FragmentMutedTags;
import ceui.lisa.fragments.FragmentSB;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

public class AddTagDialog extends BaseDialog<DialogAddTagBinding> {

    private int type = 0;

    public static AddTagDialog newInstance(int type) {
        Bundle args = new Bundle();
        args.putInt(Params.DATA_TYPE, type);
        AddTagDialog fragment = new AddTagDialog();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        type = bundle.getInt(Params.DATA_TYPE);
    }

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
                    Common.showToast("请输入标签名", sure, 3);
                    return;
                }

                if (type == 0 && getParentFragment() instanceof FragmentSB) {
                    ((FragmentSB) getParentFragment()).addTag(baseBind.tagName.getText().toString());
                } else if (type == 1 && getParentFragment() instanceof FragmentMutedTags) {
                    ((FragmentMutedTags) getParentFragment())
                            .addMutedTag(baseBind.tagName.getText().toString());
                }

                dismiss();
            }
        });
        if (type == 0) {
            baseBind.dialogTitle.setText("添加标签");
            baseBind.tagName.setHint("请输入标签（可视为收藏夹）名");
        } else if (type == 1) {
            baseBind.dialogTitle.setText("添加屏蔽标签");
            baseBind.tagName.setHint("请输入标签名");
        }
        cancel = v.findViewById(R.id.cancel);
        cancel.setOnClickListener(view -> dismiss());
    }

    @Override
    void initData() {

    }
}
