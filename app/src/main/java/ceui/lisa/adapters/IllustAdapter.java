package ceui.lisa.adapters;

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
import androidx.lifecycle.LifecycleOwner;

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
import ceui.lisa.models.IllustsBean;
import ceui.lisa.transformer.LargeBitmapScaleTransformer;
import ceui.lisa.transformer.UniformScaleTransformation;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.ui.task.LoadTask;
import ceui.pixiv.ui.task.NamedUrl;
import ceui.pixiv.ui.task.TaskPool;
import ceui.pixiv.ui.task.TaskStatus;

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
    public void onViewRecycled(@NonNull ViewHolder<RecyIllustDetailBinding> holder) {
        super.onViewRecycled(holder);
        // Cancel any in-flight Glide load targeting this ImageView so a late-arriving
        // bitmap from the previous bind can't leak into the recycled holder.
        Glide.with(mFragment).clear(holder.baseBind.illust);
        holder.baseBind.illust.setTag(R.id.tag_image_url, null);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder<RecyIllustDetailBinding> holder, int position) {
        super.onBindViewHolder(holder, position);
        if(longPressDownload && mActivity instanceof BaseActivity<?>){
            holder.itemView.setOnLongClickListener(v -> {
                IllustDownload.downloadIllustCertainPage(allIllust, position, (BaseActivity<?>) mActivity);
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
        final String imageUrl;
        boolean isLoadOriginalImage = Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal;
        if (isLoadOriginalImage) {
            imageUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_ORIGINAL);
        } else {
            imageUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_LARGE);
        }

        // Stamp the ImageView with the URL this bind intends to display. Task LiveData
        // observers are attached to the fragment's viewLifecycleOwner and persist across
        // rebinds; a late-arriving callback from a previous position would otherwise
        // push the wrong bitmap into a recycled holder. Every callback below checks the
        // tag before mutating UI so stale callbacks become no-ops.
        holder.baseBind.illust.setTag(R.id.tag_image_url, imageUrl);

        holder.baseBind.reload.setOnClickListener(v -> {
            holder.baseBind.reload.setVisibility(View.GONE);
            holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
            holder.baseBind.progressLayout.donutProgress.setProgress(0);
            TaskPool.INSTANCE.removeTask(imageUrl);
            loadIllust(holder, position, changeSize);
        });

        LifecycleOwner lifecycleOwner = mFragment.getViewLifecycleOwner();
        LoadTask task = TaskPool.INSTANCE.getLoadTask(new NamedUrl("", imageUrl), true);
        Common.showLog("一级详情页 loadIllust: taskId=" + task.getTaskId() + ", url=" + imageUrl);

        task.getStatus().observe(lifecycleOwner, status -> {
            if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return;
            if (status instanceof TaskStatus.Executing) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                holder.baseBind.progressLayout.donutProgress.setProgress(
                        ((TaskStatus.Executing) status).getPercentage()
                );
            } else if (status instanceof TaskStatus.Finished) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
            } else if (status instanceof TaskStatus.Error) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                holder.baseBind.reload.setVisibility(View.VISIBLE);
            }
        });

        task.getResult().observe(lifecycleOwner, file -> {
            if (file == null) return;
            if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return;
            holder.baseBind.reload.setVisibility(View.GONE);
            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);

            RequestManager requestManager = mFragment != null ? Glide.with(mFragment) : Glide.with(mContext);
            requestManager
                    .asBitmap()
                    .load(file)
                    .transform(new LargeBitmapScaleTransformer())
                    .transition(BitmapTransitionOptions.withCrossFade())
                    .listener(new RequestListener<Bitmap>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                            if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                            holder.baseBind.reload.setVisibility(View.VISIBLE);
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                            if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                            holder.baseBind.reload.setVisibility(View.GONE);
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                            if (isLoadOriginalImage) {
                                Shaft.getMMKV().encode(imageUrl, true);
                            }
                            return false;
                        }
                    })
                    .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));
        });
    }
}
