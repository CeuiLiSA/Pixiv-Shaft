package ceui.lisa.dialogs;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.DialogMuteTagBinding;
import ceui.lisa.helper.IllustNovelFilter;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class MuteDialog extends BaseDialog<DialogMuteTagBinding> {

    private IllustsBean mIllust;
    private final List<TagsBean> selected = new ArrayList<>();
    private final List<Boolean> muteNotEffect = new ArrayList<>();

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
        // 计算 tag 状态
        List<TagsBean> muted = IllustNovelFilter.getMutedTags();
        List<TagsBean> illustTags = mIllust.getTags();
        Set<Integer> selectedIndex = new HashSet<>();
        for (int i = 0; i < illustTags.size(); i++) {
            TagsBean tagsBean = illustTags.get(i);
            muteNotEffect.add(i,false);
            for (TagsBean mutedBean : muted) {
                if (tagsBean.getName().equals(mutedBean.getName())) {
                    if(mutedBean.isEffective()){
                        selectedIndex.add(i);
                    }else{
                        muteNotEffect.set(i,true);
                    }
                    break;
                }
            }
        }

        TagAdapter<TagsBean> adapter = new TagAdapter<TagsBean>(mIllust.getTags()) {
            @Override
            public View getView(FlowLayout parent, int position, TagsBean o) {
                View view = View.inflate(mContext, R.layout.recy_single_tag_text, null);
                TextView tag = view.findViewById(R.id.tag_title);
                tag.setText(o.getName());
                if (muteNotEffect.get(position)) {
                    tag.setBackgroundResource(R.drawable.tag_stroke_checked_not_enable_bg);
                }
                return view;
            }

            @Override
            public void onSelected(int position, View view) {
                super.onSelected(position, view);
                ((TextView) view).setTextColor(Common.resolveThemeAttribute(mContext, R.attr.colorPrimary));
                view.setBackgroundResource(R.drawable.tag_stroke_checked_bg);
                selected.add(mIllust.getTags().get(position));
            }

            @Override
            public void unSelected(int position, View view) {
                super.unSelected(position, view);
                if (muteNotEffect.get(position)) {
                    view.setBackgroundResource(R.drawable.tag_stroke_checked_not_enable_bg);
                }else{
                    view.setBackgroundResource(R.drawable.tag_stroke_bg);
                }
                ((TextView) view).setTextColor(getResources().getColor(R.color.tag_text_unselect));
                selected.remove(mIllust.getTags().get(position));
            }
        };
        baseBind.tagLayout.setAdapter(adapter);
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
                    Common.showToast(getString(R.string.string_165));
                }
            }
        });
        baseBind.other.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "标签屏蔽记录");
                mContext.startActivity(intent);
                dismiss();
            }
        });

        //默认选中已屏蔽的标签
        if (selectedIndex.size() != 0) {
            adapter.setSelectedList(selectedIndex);
        }
    }

    @Override
    void initData() {

    }

    @Override
    public void initBundle(Bundle bundle) {
        mIllust = ((IllustsBean) bundle.getSerializable(Params.CONTENT));
    }
}
