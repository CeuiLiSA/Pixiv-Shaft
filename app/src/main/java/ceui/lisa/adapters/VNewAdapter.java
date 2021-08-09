package ceui.lisa.adapters;

import android.content.Context;
import android.view.View;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentSingleNovelBinding;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.utils.Common;

public class VNewAdapter extends BaseAdapter<NovelDetail.NovelChapterBean, FragmentSingleNovelBinding> {

    private int textColor = 0;

    public VNewAdapter(List<NovelDetail.NovelChapterBean> targetList, Context context) {
        super(targetList, context);
        textColor = Common.getNovelTextColor();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_single_novel;
    }

    @Override
    public void bindData(NovelDetail.NovelChapterBean target, ViewHolder<FragmentSingleNovelBinding> bindView, int position) {
        String chapterContent = target.getChapterContent();

        if (position == 0) {
            bindView.baseBind.head.setVisibility(View.VISIBLE);
        } else {
            bindView.baseBind.head.setVisibility(View.GONE);
        }

        bindView.baseBind.chapter.setText(target.getChapterName());

        if (position == allItems.size() - 1) {
            bindView.baseBind.bottom.setVisibility(View.VISIBLE);
            bindView.baseBind.endText.setVisibility(View.VISIBLE);
        } else {
            bindView.baseBind.bottom.setVisibility(View.GONE);
            bindView.baseBind.endText.setVisibility(View.GONE);
        }

        bindView.baseBind.partIndex.setVisibility(View.GONE);
        bindView.baseBind.novelDetail.setText(chapterContent);

        bindView.baseBind.chapter.setTextColor(textColor);
        bindView.baseBind.novelDetail.setTextColor(textColor);
        bindView.baseBind.endText.setTextColor(textColor);
    }
}
