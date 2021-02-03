package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.databinding.DataBindingUtil;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.RecyDownloadedBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

//已下载
public class DownloadedAdapter extends BaseAdapter<DownloadEntity, RecyDownloadedBinding> {

    private int imageSize;
    private int novelImageSize;
    private SimpleDateFormat mTime = new SimpleDateFormat(
            mContext.getResources().getString(R.string.string_350),
            Locale.getDefault());

    public DownloadedAdapter(List<DownloadEntity> targetList, Context context) {
        super(targetList, context);
        imageSize = DensityUtil.dp2px(140.0f);
        novelImageSize = DensityUtil.dp2px(110.0f);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_downloaded;
    }

    @Override
    public void bindData(DownloadEntity target,
                         ViewHolder<RecyDownloadedBinding> bindView, int position) {

        String fileName = allIllust.get(position).getFileName();
        if (!TextUtils.isEmpty(fileName) && fileName.contains(Params.NOVEL_KEY)) {
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.width = novelImageSize;
            bindView.baseBind.illustImage.setLayoutParams(params);

            NovelBean current = Shaft.sGson.fromJson(allIllust.get(position).getIllustGson(), NovelBean.class);
            bindView.baseBind.illustImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(mContext)
                    .load(GlideUtil.getUrl(current.getImage_urls().getMedium()))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
            bindView.baseBind.title.setText(current.getTitle());
            bindView.baseBind.author.setText(String.format("by: %s", current.getUser().getName()));
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
            if (!TextUtils.isEmpty(allIllust.get(position).getFileName()) &&
                    allIllust.get(position).getFileName().contains(".zip")) {
                bindView.baseBind.illustImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                Glide.with(mContext)
                        .load(R.mipmap.zip)
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.illustImage);
            } else {
                if (currentIllust.isGif()) {
                    bindView.baseBind.illustImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    Glide.with(mContext)
                            .load(currentIllust.getImage_urls().getMedium())
                            .placeholder(R.color.light_bg)
                            .into(bindView.baseBind.illustImage);
                } else {
                    bindView.baseBind.illustImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    Glide.with(mContext)
                            .load(allIllust.get(position).getFilePath())
                            .placeholder(R.color.light_bg)
                            .into(bindView.baseBind.illustImage);
                }
            }
            bindView.baseBind.title.setText(allIllust.get(position).getFileName());
            bindView.baseBind.author.setText(String.format("by: %s", currentIllust.getUser().getName()));
            bindView.baseBind.time.setText(mTime.format(allIllust.get(position).getDownloadTime()));

            if (currentIllust.getPage_count() == 1) {
                bindView.baseBind.pSize.setVisibility(View.GONE);
            } else {
                bindView.baseBind.pSize.setVisibility(View.VISIBLE);
                bindView.baseBind.pSize.setText(String.format("%dP", currentIllust.getPage_count()));
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
        ((DownloadedHolder) bindView).spring.setCurrentValue(-400);
        ((DownloadedHolder) bindView).spring.setEndValue(0);
    }

    @Override
    public ViewHolder<RecyDownloadedBinding> getNormalItem(ViewGroup parent) {
        return new DownloadedHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(mContext), mLayoutID, parent, false
                )
        );
    }
}
