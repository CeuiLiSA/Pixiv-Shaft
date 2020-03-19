package ceui.lisa.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.databinding.DataBindingUtil;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.models.IllustsBean;

//已下载
public class DownloadedAdapter extends BaseAdapter<DownloadEntity, RecyViewHistoryBinding> {

    private int imageSize = 0;
    private SimpleDateFormat mTime = new SimpleDateFormat("MM月dd日 HH: mm");

    public DownloadedAdapter(List<DownloadEntity> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp)) / 2;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_view_history;
    }

    @Override
    public void bindData(DownloadEntity target,
                         ViewHolder<RecyViewHistoryBinding> bindView, int position) {
        ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
        params.height = imageSize;
        params.width = imageSize;
        bindView.baseBind.illustImage.setLayoutParams(params);

        IllustsBean currentIllust = Shaft.sGson.fromJson(allIllust.get(position).getIllustGson(), IllustsBean.class);
        Glide.with(mContext)
                .load(allIllust.get(position).getFilePath())
                .placeholder(R.color.light_bg)
                .into(bindView.baseBind.illustImage);
        bindView.baseBind.title.setText(allIllust.get(position).getFileName());
        bindView.baseBind.author.setText("by: " + currentIllust.getUser().getName());
        bindView.baseBind.time.setText(mTime.format(allIllust.get(position).getDownloadTime()));

        if (currentIllust.getPage_count() == 1) {
            bindView.baseBind.pSize.setVisibility(View.GONE);
        } else {
            bindView.baseBind.pSize.setVisibility(View.VISIBLE);
            bindView.baseBind.pSize.setText(currentIllust.getPage_count() + "P");
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
