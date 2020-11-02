package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.ybq.android.spinkit.style.Wave;

import java.util.HashMap;
import java.util.Map;

import ceui.lisa.R;
import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.RecyIllustDetailBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.transformer.UniformScaleTransformation;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;

public class IllustAdapter extends RecyclerView.Adapter<ViewHolder<RecyIllustDetailBinding>> {

    private Context mContext;
    private IllustsBean allIllust;
    private int imageSize;
    private Map<Integer, Boolean> hasLoad = new HashMap<>();
    private int maxHeight;

    public IllustAdapter(Context context, IllustsBean illustsBean, int maxHeight){
        Common.showLog("IllustAdapter maxHeight " + maxHeight );
        mContext = context;
        allIllust = illustsBean;
        this.maxHeight = maxHeight;
        imageSize = mContext.getResources().getDisplayMetrics().widthPixels;
        hasLoad.clear();
        for (int i = 0; i < allIllust.getPage_count(); i++) {
            hasLoad.put(i, false);
        }
    }

    @NonNull
    @Override
    public ViewHolder<RecyIllustDetailBinding> onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder<>(DataBindingUtil.inflate(
                LayoutInflater.from(mContext), R.layout.recy_illust_detail, parent, false
        ));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder<RecyIllustDetailBinding> holder, int position) {
        Wave wave = new Wave();
        holder.baseBind.progress.setIndeterminateDrawable(wave);
        if (position == 0) {
            if (allIllust.getPage_count() == 1) {

                //获取屏幕imageview的宽高比率
                float screenRatio = (float) imageSize / maxHeight;
                //获取作品的宽高比率
                float illustRatio = (float) allIllust.getWidth() / allIllust.getHeight();

                if (Math.abs(illustRatio - screenRatio) < 0.1f) {//如果宽高相近，直接CENTER_CROP 填充
                    holder.baseBind.illust.setScaleType(ImageView.ScaleType.CENTER_CROP);

                    ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
                    params.width = imageSize;
                    params.height = maxHeight;
                    holder.baseBind.illust.setLayoutParams(params);
                    loadIllust(holder, position, false);
                } else {
                    //如果宽高差的比较大，FIT_CENTER 填充
                    if (illustRatio < screenRatio) {
                        holder.baseBind.illust.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        loadIllust(holder, position, true);
                    } else {
                        holder.baseBind.illust.setScaleType(ImageView.ScaleType.FIT_CENTER);

                        ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
                        params.width = imageSize;
                        params.height = maxHeight;
                        holder.baseBind.illust.setLayoutParams(params);
                        loadIllust(holder, position, false);
                    }
                }


            } else {
                holder.baseBind.illust.setScaleType(ImageView.ScaleType.CENTER_CROP);
                loadIllust(holder, position, true);
            }
        } else {
            holder.baseBind.illust.setScaleType(ImageView.ScaleType.CENTER_CROP);
            loadIllust(holder, position, true);
        }

        if (hasLoad.get(position)) {
            Common.showLog("hasLoad 隐藏掉 " + position);
            holder.baseBind.progress.setVisibility(View.INVISIBLE);
        } else {
            Common.showLog("hasLoad 显示掉 " + position);
            holder.baseBind.progress.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, ImageDetailActivity.class);
            intent.putExtra("illust", allIllust);
            intent.putExtra("dataType", "二级详情");
            intent.putExtra("index", position);
            mContext.startActivity(intent);
        });
    }

    /**
     *
     * @param holder
     * @param position
     * @param changeSize 是否自动计算宽高
     */
    private void loadIllust(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize){
        holder.baseBind.progress.setVisibility(View.VISIBLE);
        Glide.with(mContext)
                .asBitmap()
                .load(Shaft.sSettings.isFirstImageSize() ?
                        GlideUtil.getOriginal(allIllust, position) :
                        GlideUtil.getLargeImage(allIllust, position))
                .transition(BitmapTransitionOptions.withCrossFade())
                .error(getBuilder(holder.baseBind.progress, position))
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                        hasLoad.put(position, false);
                        Common.showLog("IllustAdapter onLoadFailed " + position);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        holder.baseBind.progress.setVisibility(View.INVISIBLE);
                        Common.showLog("IllustAdapter onResourceReady " + position);
                        hasLoad.put(position, true);
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));
    }

    @Override
    public int getItemCount() {
        return allIllust.getPage_count();
    }

    public RequestBuilder<Bitmap> getBuilder(ProgressBar progressBar, int position) {
        return Glide.with(mContext)
                .asBitmap()
                .load(Shaft.sSettings.isFirstImageSize() ?
                        GlideUtil.getOriginal(allIllust, position) :
                        GlideUtil.getLargeImage(allIllust, position))
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                        hasLoad.put(position, false);
                        Common.showLog("IllustAdapter onLoadFailed " + position);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        progressBar.setVisibility(View.INVISIBLE);
                        Common.showLog("IllustAdapter onResourceReady " + position);
                        hasLoad.put(position, true);
                        return false;
                    }
                });
    }
}
