package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.databinding.DataBindingUtil;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import jp.wasabeef.glide.transformations.BlurTransformation;

import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

//屏蔽作品历史
public class MuteWorksAdapter extends BaseAdapter<MuteEntity, RecyViewHistoryBinding> {

    private int illustImageSize = 0, novelImageSize = 0;
    private final SimpleDateFormat mTime = new SimpleDateFormat(
            mContext.getResources().getString(R.string.string_350),
            Locale.getDefault());

    public MuteWorksAdapter(List<MuteEntity> targetList, Context context) {
        super(targetList, context);
        illustImageSize = (mContext.getResources().getDisplayMetrics().widthPixels -
                mContext.getResources().getDimensionPixelSize(R.dimen.four_dp)) / 2;
        novelImageSize = DensityUtil.dp2px(110.0f);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_view_history;
    }

    @Override
    public void bindData(MuteEntity target, ViewHolder<RecyViewHistoryBinding> bindView, int position) {
        if (target.getType() == 1) {
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.height = illustImageSize;
            params.width = illustImageSize;
            bindView.baseBind.illustImage.setLayoutParams(params);

            IllustsBean current = Shaft.sGson.fromJson(allItems.get(position).getTagJson(), IllustsBean.class);
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(current))
                    .apply(bitmapTransform(new BlurTransformation(25, 3)))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
            bindView.baseBind.title.setText(current.getTitle());
            bindView.baseBind.author.setText(String.format("by: %s", current.getUser().getName()));

            if (current.isGif()) {
                bindView.baseBind.pSize.setVisibility(View.VISIBLE);
                bindView.baseBind.pSize.setText("GIF");
            } else {
                if (current.getPage_count() == 1) {
                    bindView.baseBind.pSize.setVisibility(View.GONE);
                } else {
                    bindView.baseBind.pSize.setVisibility(View.VISIBLE);
                    bindView.baseBind.pSize.setText(String.format("%dP", current.getPage_count()));
                }
            }

            if (mOnItemClickListener != null) {
                bindView.itemView.setOnClickListener(v ->
                        mOnItemClickListener.onItemClick(v, position, 0));
                bindView.baseBind.author.setOnClickListener(v -> {
                    bindView.baseBind.author.setTag(current.getUser().getId());
                    mOnItemClickListener.onItemClick(bindView.baseBind.author, position, 1);
                });
                bindView.baseBind.deleteItem.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mOnItemClickListener.onItemClick(v, position, 2);
                    }
                });
            }
        } else if (target.getType() == 2) {
            ViewGroup.LayoutParams params = bindView.baseBind.illustImage.getLayoutParams();
            params.width = novelImageSize;
            bindView.baseBind.illustImage.setLayoutParams(params);

            NovelBean current = Shaft.sGson.fromJson(allItems.get(position).getTagJson(), NovelBean.class);
            Glide.with(mContext)
                    .load(GlideUtil.getUrl(current.getImage_urls().getMedium()))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
            bindView.baseBind.title.setText(current.getTitle());
            bindView.baseBind.author.setText(String.format("by: %s", current.getUser().getName()));

            bindView.baseBind.pSize.setVisibility(View.VISIBLE);
            bindView.baseBind.pSize.setText("小说");

            if (mOnItemClickListener != null) {
                bindView.itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, current);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                    intent.putExtra("hideStatusBar", true);
                    mContext.startActivity(intent);
                });
                bindView.baseBind.deleteItem.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mOnItemClickListener.onItemClick(v, position, 2);
                    }
                });
            }
        }

        bindView.baseBind.time.setText(mTime.format(allItems.get(position).getSearchTime()));

        //从-400 丝滑滑动到0
        ((SpringHolder) bindView).spring.setCurrentValue(-400);
        ((SpringHolder) bindView).spring.setEndValue(0);
    }

    @Override
    public ViewHolder<RecyViewHistoryBinding> getNormalItem(ViewGroup parent) {
        return new SpringHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(mContext),
                        mLayoutID,
                        parent,
                        false
                )
        );
    }
}
