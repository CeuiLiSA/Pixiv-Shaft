package ceui.lisa.adapters;

import android.content.Context;
import android.view.ViewGroup;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentSingleNovelBinding;
import ceui.lisa.databinding.RecyArticalBinding;
import ceui.lisa.model.SpotlightArticlesBean;
import ceui.lisa.utils.GlideUtil;

public class VAdapter extends BaseAdapter<String, FragmentSingleNovelBinding> {


    public VAdapter(List<String> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_single_novel;
    }

    @Override
    public void bindData(String target, ViewHolder<FragmentSingleNovelBinding> bindView, int position) {
        bindView.baseBind.novelDetail.setText(target);
    }
}
