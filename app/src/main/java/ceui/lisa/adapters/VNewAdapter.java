package ceui.lisa.adapters;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentSingleNovelBinding;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.utils.Common;

public class VNewAdapter extends BaseAdapter<NovelDetail.NovelChapterBean, FragmentSingleNovelBinding> {

    private int textColor = 0;
    private int textSize = 16;
    private static final String PAYLOAD_TEXT_SIZE = "text_size";

    public VNewAdapter(List<NovelDetail.NovelChapterBean> targetList, Context context) {
        super(targetList, context);
        textColor = Common.getNovelTextColor();
        textSize = Common.getNovelTextSize();
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

        bindView.baseBind.novelDetail.setTextSize(textSize);
        bindView.baseBind.chapter.setTextSize(textSize);
        bindView.baseBind.endText.setTextSize(textSize);

        bindView.baseBind.chapter.setTextColor(textColor);
        bindView.baseBind.novelDetail.setTextColor(textColor);
        bindView.baseBind.endText.setTextColor(textColor);
    }

    public void updateTextSize(int size) {
        this.textSize = size;
        int itemCount = getItemCount();
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_TEXT_SIZE);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!(holder instanceof ViewHolder)) {
            return;
        }

        if (!payloads.isEmpty() && PAYLOAD_TEXT_SIZE.equals(payloads.get(0))) {
            ViewHolder<FragmentSingleNovelBinding> bindView = (ViewHolder<FragmentSingleNovelBinding>) holder;
            bindView.baseBind.novelDetail.setTextSize(textSize);
            bindView.baseBind.chapter.setTextSize(textSize);
            bindView.baseBind.endText.setTextSize(textSize);
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }
}
