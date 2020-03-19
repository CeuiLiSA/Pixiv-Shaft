package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

//浏览历史
public class HistoryAdapter extends BaseAdapter<IllustHistoryEntity, RecyViewHistoryBinding> {

    private int imageSize = 0;
    private SimpleDateFormat mTime = new SimpleDateFormat("MM月dd日 HH: mm");

    public HistoryAdapter(List<IllustHistoryEntity> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp)) / 2;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_view_history;
    }

    @Override
    public void bindData(IllustHistoryEntity target, ViewHolder<RecyViewHistoryBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        bindView.baseBind.illustImage.setLayoutParams(params);

        IllustsBean currentIllust = Shaft.sGson.fromJson(allIllust.get(position).getIllustJson(), IllustsBean.class);
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(currentIllust))
                .placeholder(R.color.light_bg)
                .into(bindView.baseBind.illustImage);
        bindView.baseBind.title.setText(currentIllust.getTitle());
        bindView.baseBind.author.setText("by: " + currentIllust.getUser().getName());
        bindView.baseBind.time.setText(mTime.format(allIllust.get(position).getTime()));

        if(currentIllust.isGif()){
            bindView.baseBind.pSize.setText("GIF");
        } else {
            if (currentIllust.getPage_count() == 1) {
                bindView.baseBind.pSize.setVisibility(View.GONE);
            } else {
                bindView.baseBind.pSize.setVisibility(View.VISIBLE);
                bindView.baseBind.pSize.setText(currentIllust.getPage_count() + "P");
            }
        }

        //从-400 丝滑滑动到0
        ((SpringHolder) bindView).spring.setCurrentValue(-400);
        ((SpringHolder) bindView).spring.setEndValue(0);

        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v ->
                    mOnItemClickListener.onItemClick(v, position, 0));
            bindView.baseBind.author.setOnClickListener(v -> {
                bindView.baseBind.author.setTag(currentIllust.getUser().getId());
                mOnItemClickListener.onItemClick(bindView.baseBind.author, position, 1);
            });
        }
    }

    @Override
    public ViewHolder<RecyViewHistoryBinding> getNormalItem(ViewGroup parent) {
        return new SpringHolder(DataBindingUtil.inflate(
                LayoutInflater.from(mContext), mLayoutID, parent, false).getRoot());
    }
}
