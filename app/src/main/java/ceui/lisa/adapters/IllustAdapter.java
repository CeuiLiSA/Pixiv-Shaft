package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.RecyIllustDetailBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.feature.HostManager;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.transformer.LargeBitmapScaleTransformer;
import ceui.lisa.transformer.UniformScaleTransformation;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUrlChild;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import me.jessyan.progressmanager.ProgressListener;
import me.jessyan.progressmanager.ProgressManager;
import me.jessyan.progressmanager.body.ProgressInfo;

public class IllustAdapter extends AbstractIllustAdapter<ViewHolder<RecyIllustDetailBinding>> {

    private final int maxHeight;
    private final FragmentActivity mActivity;
    private final Fragment mFragment;
    private static final boolean longPressDownload = Shaft.sSettings.isIllustLongPressDownload();

    public IllustAdapter(FragmentActivity activity, Fragment fragment, IllustsBean illustsBean, int maxHeight, boolean isForceOriginal) {
        Common.showLog("IllustAdapter maxHeight " + maxHeight);
        mActivity = activity;
        mContext = fragment.requireContext();
        allIllust = illustsBean;
        this.maxHeight = maxHeight;
        imageSize = mContext.getResources().getDisplayMetrics().widthPixels;
        this.isForceOriginal = isForceOriginal;
        this.mFragment = fragment;
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
        super.onBindViewHolder(holder, position);
        if(longPressDownload && mActivity instanceof BaseActivity<?>){
            holder.itemView.setOnLongClickListener(v -> {
                IllustDownload.downloadIllust(allIllust, position, (BaseActivity<?>) mActivity);
                if(Shaft.sSettings.isAutoPostLikeWhenDownload() && !allIllust.isIs_bookmarked()){
                    PixivOperate.postLikeDefaultStarType(allIllust);
                }
                return true;
            });
        }

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
        holder.baseBind.reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                holder.baseBind.reload.setVisibility(View.GONE);
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                loadIllust(holder, position, changeSize);
            }
        });
        final String imageUrl;
        if (Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal) {
            imageUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_ORIGINAL);
        } else {
            imageUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_LARGE);
        }
        ProgressManager.getInstance().addResponseListener(imageUrl, new ProgressListener() {
            @Override
            public void onProgress(ProgressInfo progressInfo) {
                holder.baseBind.progressLayout.donutProgress.setProgress(progressInfo.getPercent());
                if(progressInfo.isFinish()){
                    ProgressManager.getInstance().removeResponseListener(imageUrl,this);
                }
            }

            @Override
            public void onError(long id, Exception e) {

            }
        });

        RequestManager requestManager = this.mFragment != null ? Glide.with(this.mFragment) : Glide.with(mContext);

        requestManager
                .asBitmap()
                .load(new GlideUrlChild(imageUrl))
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                        holder.baseBind.reload.setVisibility(View.VISIBLE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.INVISIBLE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        holder.baseBind.reload.setVisibility(View.GONE);
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
