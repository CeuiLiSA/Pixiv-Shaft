package ceui.lisa.adapters;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.PixivOperate;

public class BAdapter extends BaseAdapter<TagsBean, RecyBookTagBinding> {

    private boolean isMuted = false;

    public BAdapter(List<TagsBean> targetList, Context context, boolean muted) {
        super(targetList, context);
        isMuted = muted;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_book_tag;
    }

    @Override
    public void bindData(TagsBean target, ViewHolder<RecyBookTagBinding> bindView, int position) {
        if (TextUtils.isEmpty(allIllust.get(position).getName())) {
            bindView.baseBind.starSize.setText("#全部");
        } else {
            bindView.baseBind.starSize.setText("#" + allIllust.get(position).getName());
        }

        if (allIllust.get(position).getCount() == -1) {
            bindView.baseBind.illustCount.setText("");
        } else {
            if (isMuted) {
                bindView.baseBind.illustCount.setText("取消屏蔽");
                bindView.baseBind.illustCount.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PixivOperate.unMuteTag(target);
                        allIllust.remove(target);
                        notifyItemRemoved(position);
                    }
                });
            } else {
                bindView.baseBind.illustCount.setText(allIllust.get(position).getCount() + "个作品");
            }
        }
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
