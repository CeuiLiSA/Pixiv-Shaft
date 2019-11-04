package ceui.lisa.dialogs;

import android.text.TextUtils;
import android.view.View;

import com.rengwuxian.materialedittext.MaterialEditText;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentSelectBookTag;
import ceui.lisa.utils.Common;

public class AddTagDialog extends BaseDialog {

    private MaterialEditText tagName;

    @Override
    void initLayout() {
        mLayoutID = R.layout.dialog_add_tag;
    }

    @Override
    View initView(View v) {
        tagName = v.findViewById(R.id.tag_name);
        sure = v.findViewById(R.id.sure);
        sure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (TextUtils.isEmpty(tagName.getText().toString())) {
                    Common.showToast("请输入标签名");
                    return;
                }


                if (getParentFragment() instanceof FragmentSelectBookTag) {
                    ((FragmentSelectBookTag) getParentFragment()).addTag(tagName.getText().toString());
                }

                dismiss();
            }
        });
        cancel = v.findViewById(R.id.cancel);
        cancel.setOnClickListener(view -> dismiss());
        return v;
    }

    @Override
    void initData() {

    }
}
