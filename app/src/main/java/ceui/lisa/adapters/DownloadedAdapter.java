package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.databinding.DataBindingUtil;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

//已下载
public class DownloadedAdapter extends BaseAdapter<DownloadEntity, RecyViewHistoryBinding> {

    private int imageSize;
    private int novelImageSize;
    private SimpleDateFormat mTime = new SimpleDateFormat("MM月dd日 HH: mm");

    public DownloadedAdapter(List<DownloadEntity> targetList, Context context) {
        super(targetList, context);
        imageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp)) / 2;
        novelImageSize = DensityUtil.dp2px(110.0f);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_view_history;
    }

    @Override
    public void bindData(DownloadEntity target,
                         ViewHolder<RecyViewHistoryBinding> bindView, int position) {

        String fileName = allIllust.get(position).getFileName();
        if (!TextUtils.isEmpty(fileName) && fileName.contains(Params.NOVEL_KEY)) {
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.width = novelImageSize;
            bindView.baseBind.illustImage.setLayoutParams(params);

            NovelBean current = Shaft.sGson.fromJson(allIllust.get(position).getIllustGson(), NovelBean.class);
            Glide.with(mContext)
                    .load(GlideUtil.getUrl(current.getImage_urls().getMedium()))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
            bindView.baseBind.title.setText(current.getTitle());
            bindView.baseBind.author.setText("by: " + current.getUser().getName());
            bindView.baseBind.time.setText(mTime.format(allIllust.get(position).getDownloadTime()));

            bindView.baseBind.pSize.setText(R.string.string_171);

            if (mOnItemClickListener != null) {
                bindView.itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, current);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                    intent.putExtra("hideStatusBar", true);
                    mContext.startActivity(intent);
                });
            }
        } else {
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.height = imageSize;
            params.width = imageSize;
            bindView.baseBind.illustImage.setLayoutParams(params);

            IllustsBean currentIllust = Shaft.sGson.fromJson(allIllust.get(position).getIllustGson(), IllustsBean.class);
            if (currentIllust.isGif()) {
                Glide.with(mContext)
                        .load(currentIllust.getImage_urls().getMedium())
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.illustImage);
            } else {
                Glide.with(mContext)
                        .load(allIllust.get(position).getFilePath())
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.illustImage);
            }
            bindView.baseBind.title.setText(allIllust.get(position).getFileName());
            bindView.baseBind.author.setText("by: " + currentIllust.getUser().getName());
            bindView.baseBind.time.setText(mTime.format(allIllust.get(position).getDownloadTime()));

            if (currentIllust.getPage_count() == 1) {
                bindView.baseBind.pSize.setVisibility(View.GONE);
            } else {
                bindView.baseBind.pSize.setVisibility(View.VISIBLE);
                bindView.baseBind.pSize.setText(currentIllust.getPage_count() + "P");
            }

            if (mOnItemClickListener != null) {
                bindView.itemView.setOnClickListener(v ->
                        mOnItemClickListener.onItemClick(v, position, 0));
                bindView.baseBind.author.setOnClickListener(v -> {
                    bindView.baseBind.author.setTag(currentIllust.getUser().getId());
                    mOnItemClickListener.onItemClick(bindView.baseBind.author, position, 1);
                });
            }
        }
        //从-400 丝滑滑动到0
        ((SpringHolder) bindView).spring.setCurrentValue(-400);
        ((SpringHolder) bindView).spring.setEndValue(0);
    }

    @Override
    public ViewHolder<RecyViewHistoryBinding> getNormalItem(ViewGroup parent) {
        return new SpringHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(mContext), mLayoutID, parent, false
                )
        );
    }
}
