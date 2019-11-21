package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.model.NovelBean;
import ceui.lisa.model.TagsBean;
import ceui.lisa.utils.GlideUtil;
import me.next.tagview.TagCloudView;

public class NAdapter extends BaseAdapter<NovelBean, RecyNovelBinding> {

    public NAdapter(List<NovelBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_novel;
    }

    @Override
    public void bindData(NovelBean target, ViewHolder<RecyNovelBinding> bindView, int position) {
        bindView.baseBind.title.setText(target.getTitle());
        if(target.getSeries() != null && !TextUtils.isEmpty(target.getSeries().getTitle())) {
            bindView.baseBind.series.setText(String.format("系列：%s", target.getSeries().getTitle()));
        }
        bindView.baseBind.author.setText(target.getUser().getName());
        bindView.baseBind.howManyWord.setText(target.getText_length() + "字");
        Glide.with(mContext).load(target.getImage_urls().getLarge()).into(bindView.baseBind.cover);
        Glide.with(mContext).load(GlideUtil.getHead(target.getUser())).into(bindView.baseBind.userHead);
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
