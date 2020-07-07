package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityOptionsCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.target.SizeReadyCallback;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.github.chrisbanes.photoview.PhotoView;
import com.github.ybq.android.spinkit.style.Wave;

import java.util.HashMap;
import java.util.Map;

import ceui.lisa.R;
import ceui.lisa.activities.ImageDetailActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.RecyIllustDetailBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

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
                LayoutInflater.from(mContext),
                R.layout.recy_illust_detail,
                parent,
                false
        ));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder<RecyIllustDetailBinding> holder, int position) {
        Wave wave = new Wave();
        holder.baseBind.progress.setIndeterminateDrawable(wave);
        if (hasLoad.get(position)) {
            holder.baseBind.progress.setVisibility(View.INVISIBLE);
        } else {
            holder.baseBind.progress.setVisibility(View.VISIBLE);
        }
        if (position == 0) {
            ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
            params.width = imageSize;
            params.height = maxHeight;
            holder.baseBind.illust.setLayoutParams(params);
            holder.baseBind.illust.setScaleType(ImageView.ScaleType.CENTER_CROP);
            loadIllust(holder, position, false);
        } else {
            holder.baseBind.illust.setScaleType(ImageView.ScaleType.FIT_CENTER);
            loadIllust(holder, position, true);
        }

        holder.baseBind.reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hasLoad.put(position, false);
                holder.baseBind.reload.setVisibility(View.INVISIBLE);
                holder.baseBind.progress.setVisibility(View.VISIBLE);
                loadIllust(holder, position, allIllust.getPage_count() != 0);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, ImageDetailActivity.class);
            intent.putExtra("illust", allIllust);
            intent.putExtra("dataType", "二级详情");
            intent.putExtra("index", position);
            mContext.startActivity(intent);
        });
    }

    private void loadIllust(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize){
        Glide.with(mContext)
                .asBitmap()
                .load(Shaft.sSettings.isFirstImageSize() ?
                        GlideUtil.getOriginalWithInvertProxy(allIllust, position) :
                        GlideUtil.getLargeImage(allIllust, position))
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                        holder.baseBind.reload.setVisibility(View.VISIBLE);
                        holder.baseBind.progress.setVisibility(View.INVISIBLE);
                        hasLoad.put(position, false);
                        Common.showLog("IllustAdapter onLoadFailed " + position );
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (changeSize) {
                            ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
                            params.width = imageSize;
                            params.height = imageSize * resource.getHeight() / resource.getWidth();
                            holder.baseBind.illust.setLayoutParams(params);
                        }
                        holder.baseBind.reload.setVisibility(View.INVISIBLE);
                        holder.baseBind.progress.setVisibility(View.INVISIBLE);
                        Common.showLog("IllustAdapter onResourceReady " + position );
                        hasLoad.put(position, true);
                        holder.baseBind.illust.setImageBitmap(resource);
                        return false;
                    }
                })
                .into(holder.baseBind.illust);
    }

    @Override
    public int getItemCount() {
        return allIllust.getPage_count();
    }
}
