package ceui.lisa.dialogs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.DialogMuteTagBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class MuteDialog extends BaseDialog<DialogMuteTagBinding> {

    private IllustsBean mIllust;
    private List<TagsBean> selected = new ArrayList<>();

    public static MuteDialog newInstance(IllustsBean illustsBean) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, illustsBean);
        MuteDialog fragment = new MuteDialog();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.dialog_mute_tag;
    }

    @Override
    void initView(View v) {
        baseBind.tagLayout.setAdapter(new TagAdapter<TagsBean>(mIllust.getTags()) {
            @Override
            public View getView(FlowLayout parent, int position, TagsBean o) {
                View view = LayoutInflater.from(mContext).inflate(R.layout.recy_single_tag_text, null);
                TextView tag = view.findViewById(R.id.tag_title);
                tag.setText(o.getName());
                return view;
            }

            @Override
            public void onSelected(int position, View view) {
                super.onSelected(position, view);
                view.setBackgroundResource(R.drawable.tag_stroke_checked_bg);
                ((TextView) view).setTextColor(getResources().getColor(R.color.colorPrimary));
                selected.add(mIllust.getTags().get(position));
            }

            @Override
            public void unSelected(int position, View view) {
                super.unSelected(position, view);
                view.setBackgroundResource(R.drawable.tag_stroke_bg);
                ((TextView) view).setTextColor(getResources().getColor(R.color.tag_text_unselect));
                selected.remove(mIllust.getTags().get(position));
            }
        });
        baseBind.cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        baseBind.sure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected.size() != 0) {
                    PixivOperate.muteTags(selected);
                    Common.showToast(mContext.getResources().getString(R.string.operate_success));
                    dismiss();
                } else {
                    Common.showToast("请选择要屏蔽的标签");
                }
            }
        });
    }

    @Override
    void initData() {

    }

    @Override
    public void initBundle(Bundle bundle) {
        mIllust = ((IllustsBean) bundle.getSerializable(Params.CONTENT));
    }
}
