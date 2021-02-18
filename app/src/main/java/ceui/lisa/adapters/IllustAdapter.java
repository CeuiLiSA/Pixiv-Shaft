package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.core.Container;
import ceui.lisa.databinding.RecyIllustDetailBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.feature.HostManager;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.transformer.LargeBitmapScaleTransformer;
import ceui.lisa.transformer.UniformScaleTransformation;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUrlChild;
import me.jessyan.progressmanager.ProgressListener;
import me.jessyan.progressmanager.ProgressManager;
import me.jessyan.progressmanager.body.ProgressInfo;

public class IllustAdapter extends AbstractIllustAdapter<ViewHolder<RecyIllustDetailBinding>> {

    private int maxHeight;
    private String loadImageUrl = null;

    public IllustAdapter(FragmentActivity activity, IllustsBean illustsBean, int maxHeight) {
        this(activity, illustsBean, maxHeight, false);
    }

    public IllustAdapter(FragmentActivity activity, IllustsBean illustsBean, int maxHeight, boolean isForceOriginal) {
        Common.showLog("IllustAdapter maxHeight " + maxHeight);
        mActivity = activity;
        allIllust = illustsBean;
        this.maxHeight = maxHeight;
        imageSize = mActivity.getResources().getDisplayMetrics().widthPixels;
        this.isForceOriginal = isForceOriginal;
    }

    @NonNull
    @Override
    public ViewHolder<RecyIllustDetailBinding> onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder<>(DataBindingUtil.inflate(
                LayoutInflater.from(mActivity), R.layout.recy_illust_detail, parent, false
        ));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder<RecyIllustDetailBinding> holder, int position) {
        super.onBindViewHolder(holder, position);
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
                    Common.showLog("onBindViewHolder " + maxHeight);
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
    }

    /**
     * @param holder
     * @param position
     * @param changeSize 是否自动计算宽高
     */
    private void loadIllust(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize) {
        if (Dev.isDev) {
            return;
        }
        final String imageUrl;
        if (Shaft.sSettings.isShowOriginalImage() || isForceOriginal) {
            imageUrl = IllustDownload.getUrl(allIllust, position);
        } else {
            if (allIllust.getPage_count() == 1) {
                imageUrl = HostManager.get().replaceUrl(allIllust.getImage_urls().getLarge());
            } else {
                imageUrl = HostManager.get().replaceUrl(allIllust.getMeta_pages().get(position).getImage_urls().getLarge());
            }
        }
        loadImageUrl = new String(imageUrl);
        ProgressManager.getInstance().addResponseListener(imageUrl, new ProgressListener() {
            @Override
            public void onProgress(ProgressInfo progressInfo) {
                holder.baseBind.progressLayout.donutProgress.setProgress(progressInfo.getPercent());
            }

            @Override
            public void onError(long id, Exception e) {

            }
        });
//
//
//        GlideUrlChild child = Container.get().getGlideUrl(imageUrl);
//        if (child == null) {
//            child = new GlideUrlChild(imageUrl);
//            Container.get().addLoadingUrl(imageUrl, child);
//            Common.showLog("用了新建的");
//        } else {
//            Common.showLog("用了已有的");
//        }

        Glide.with(mActivity)
                .asBitmap()
                .load(new GlideUrlChild(imageUrl))
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.INVISIBLE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.INVISIBLE);
                        if (isForceOriginal) {
                            Shaft.getMMKV().encode(imageUrl, true);
                        }
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));
    }
}
