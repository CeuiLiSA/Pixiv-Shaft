package ceui.lisa.adapters;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import timber.log.Timber;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadDao;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.RecyIllustDetailBinding;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.transformer.LargeBitmapScaleTransformer;
import ceui.lisa.transformer.UniformScaleTransformation;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUrlChild;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.utils.SketchPreloader;
import ceui.pixiv.imageloader.ImageLoadState;
import ceui.pixiv.imageloader.ImageLoadTask;
import ceui.pixiv.imageloader.ImageLoaderV3;
import ceui.pixiv.ui.task.TaskStatus;

public class IllustAdapter extends AbstractIllustAdapter<ViewHolder<RecyIllustDetailBinding>> {
    private static final boolean DEBUG_DELAY_ORIGINAL = false;  // true=延迟3秒, false=正常
    private static final int DEBUG_DELAY_MS = 3000;

    /** Reports per-page LoadTask status changes to the host (used by V3's retry-all banner). */
    public interface PageStatusListener {
        void onStatusChanged(int position, @NonNull TaskStatus status);
    }

    private final int maxHeight;
    private final FragmentActivity mActivity;
    private final Fragment mFragment;
    private static final boolean longPressDownload = Shaft.sSettings.isIllustLongPressDownload();

    @Nullable
    private PageStatusListener pageStatusListener;

    /**
     * 页码 -> 已下载文件 Uri。后台扫一次 illust_download_table，命中的页在绑定时
     * 直接 Glide 读本地文件，免得详情页(尤其多图「展开」后)又回 pixiv 重新下。
     * 见用户反馈：未展开时下载，展开后第二张及之后被重新加载。
     */
    private final Map<Integer, Uri> localPageUris = new ConcurrentHashMap<>();
    private volatile boolean localScanRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public IllustAdapter(FragmentActivity activity, Fragment fragment, IllustsBean illustsBean, int maxHeight, boolean isForceOriginal) {
        Common.showLog("IllustAdapter maxHeight " + maxHeight);
        mActivity = activity;
        mContext = fragment.requireContext();
        allIllust = illustsBean;
        this.maxHeight = maxHeight;
        imageSize = mContext.getResources().getDisplayMetrics().widthPixels;
        this.isForceOriginal = isForceOriginal;
        this.mFragment = fragment;
        scanLocalDownloads();
    }

    public void setPageStatusListener(@Nullable PageStatusListener listener) {
        this.pageStatusListener = listener;
    }

    /**
     * 后台扫描该作品已下载的页 → 本地文件 Uri。命中后回主线程刷新，绑定时优先走本地。
     * 调两次：构造时(覆盖「打开前就下好了」)、展开时(覆盖「未展开时下载，再展开」)。
     * 页码 → 文件名用 {@link FileCreator#customFileName}，与下载时写库的 fileName 同源，
     * 所以是精确的逐页匹配，不依赖文件名字典序，分图缺页也不会错位。
     */
    public void scanLocalDownloads() {
        final IllustsBean illust = allIllust;
        if (illust == null || localScanRunning || illust.isGif()) {
            return;
        }
        final int pageCount = Math.max(illust.getPage_count(), 1);
        final long illustId = illust.getId();
        localScanRunning = true;
        new Thread(() -> {
            final Map<Integer, Uri> found = new HashMap<>();
            try {
                DownloadDao dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao();
                for (int i = 0; i < pageCount; i++) {
                    // 页码 → 下载文件名(与写库时同源)→ 主键精确查。命中就记下本地 Uri。
                    DownloadEntity e = dao.getDownloadByFileName(FileCreator.customFileName(illust, i));
                    if (e != null && e.getFilePath() != null && !e.getFilePath().isEmpty()) {
                        try {
                            found.put(i, Uri.parse(e.getFilePath()));
                        } catch (Exception ignore) {
                            // 坏 URI 跳过，该页照常走网络
                        }
                    }
                }
            } catch (Throwable t) {
                Timber.w(t, "[IllustAdapter] scanLocalDownloads failed, id=%d", illustId);
            }
            mainHandler.post(() -> {
                localScanRunning = false;
                boolean changed = false;
                for (Map.Entry<Integer, Uri> en : found.entrySet()) {
                    if (localPageUris.put(en.getKey(), en.getValue()) == null) {
                        changed = true;
                    }
                }
                if (changed) {
                    notifyDataSetChanged();
                }
            });
        }).start();
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
        // Detach this holder's LoadTask observers (see loadIllust) so they don't outlive
        // the bind and pile up on the per-URL task's LiveData.
        detachTaskObservers(holder);
        // Cancel any in-flight Glide load targeting these ImageViews so a late-arriving
        // bitmap from the previous bind can't leak into the recycled holder.
        Glide.with(mFragment).clear(holder.baseBind.illust);
        Glide.with(mFragment).clear(holder.baseBind.illustHd);
        holder.baseBind.illustHd.setImageDrawable(null);
        holder.baseBind.illustHd.setVisibility(View.GONE);
        holder.baseBind.illust.setTag(R.id.tag_image_url, null);
    }

    /**
     * Remove the status/result observers registered by the previous {@link #loadIllust} on
     * this holder, if any. The observers are attached to the fragment's viewLifecycleOwner
     * (not the holder), so without this they would survive every rebind and accumulate
     * unbounded on the shared, per-URL {@link LoadTask} LiveData — each progress tick then
     * fans out to all of them, and the leaked lambdas pin recycled holders/bitmaps. On a
     * 50–60 page artwork that compounds into severe scroll jank. See issue #912.
     */
    private void detachTaskObservers(@NonNull ViewHolder<RecyIllustDetailBinding> holder) {
        Object prev = holder.itemView.getTag(R.id.tag_task_observers);
        if (prev instanceof Runnable) {
            ((Runnable) prev).run();
            holder.itemView.setTag(R.id.tag_task_observers, null);
        }
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
        // 先用元数据设置一个初始高度（占位），但不要设太大避免白边
        int iw = allIllust.getWidth();
        int ih = allIllust.getHeight();
        /* 废案
        boolean hasValidDims = iw > 0 && ih > 0;

        // 所有页面统一：根据图片宽高比计算高度
        if (hasValidDims) {
            int naturalHeight = Math.round((float) imageSize * ih / iw);
            int targetHeight;
            if (position == 0 && allIllust.getPage_count() == 1) {
                // 单图：最小高度为 maxHeight（占位）
                targetHeight = maxHeight > 0 ? Math.max(naturalHeight, maxHeight) : naturalHeight;
            } else {
                // 多图任意页 或 单图多页的第一页：精确匹配自然高度
                targetHeight = naturalHeight;
            }

            ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
            params.width = imageSize;
            params.height = targetHeight;
            holder.baseBind.illust.setLayoutParams(params);
        } else {
            // fallback: 用 maxHeight 兜底
            int fallbackHeight = maxHeight > 0 ? maxHeight : holder.baseBind.illust.getLayoutParams().height;
            holder.baseBind.illust.getLayoutParams().height = fallbackHeight;
        }
        */
        if (iw > 0 && ih > 0) {
            int naturalHeight = Math.round((float) imageSize * ih / iw);
            ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
            params.width = imageSize;
            params.height = naturalHeight;
            holder.baseBind.illust.setLayoutParams(params);
        }

        holder.baseBind.illust.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 调用统一的 loadIllust（large 会再次修正高度）
        loadIllust(holder, position, false);
    }

    /**
     * @param holder
     * @param position
     * @param changeSize 是否自动计算宽高
     */
    private void loadIllust(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize) {
        detachTaskObservers(holder);

        Glide.with(mFragment).clear(holder.baseBind.illustHd);
        holder.baseBind.illustHd.setImageDrawable(null);
        holder.baseBind.illustHd.setVisibility(View.GONE);

        Uri localUri = localPageUris.get(position);
        if (localUri != null) {
            loadFromLocalFile(holder, position, changeSize, localUri);
            return;
        }

        boolean loadOriginal = Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal;
        final String largeUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_LARGE);
        final String targetUrl = loadOriginal
                ? IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_ORIGINAL)
                : largeUrl;

        holder.baseBind.illust.setTag(R.id.tag_image_url, targetUrl);
        holder.baseBind.reload.setVisibility(View.GONE);
        holder.baseBind.reload.setOnClickListener(v -> loadIllust(holder, position, changeSize));
        holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);

        // 关键改动：large 加载时确定高度，之后不再变动
        // 先用元数据预估，large 加载完成后用 large 实际尺寸修正（只这一次）
        Glide.with(mFragment)
                .asBitmap()
                .load(new GlideUrlChild(largeUrl))
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                        if (!targetUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        holder.baseBind.reload.setVisibility(View.VISIBLE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!targetUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;

                        // 只在这里调整一次高度：用 large 图实际尺寸
                        int naturalHeight = Math.round((float) imageSize * resource.getHeight() / resource.getWidth());
                        ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
                        if (params.height != naturalHeight) {
                            params.height = naturalHeight;
                            holder.baseBind.illust.requestLayout();
                        }
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));

        // 如果开启原图模式，原图加载完成后只替换内容，不调整高度
        if (loadOriginal) {
            loadOriginalOverlay(holder, position, targetUrl);
        }
    }

    /**
     * 原图 overlay：加载原图，只替换内容，不调整高度
     */
    private void loadOriginalOverlay(ViewHolder<RecyIllustDetailBinding> holder, int position, String originalUrl) {
        String shortUrl = originalUrl.substring(originalUrl.lastIndexOf('/') + 1);
        ImageLoadTask task = ImageLoaderV3.obtain(originalUrl);
        if (task.getState().getValue() instanceof ImageLoadState.Error) {
            task.retry();
        }

        final Observer<ImageLoadState> stateObserver = state -> {
            if (!originalUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) {
                return;
            }
            if (state instanceof ImageLoadState.Loading) {
                int percent = ((ImageLoadState.Loading) state).getPercent();
                holder.baseBind.progressLayout.donutProgress.setProgress(percent);
                reportPageStatus(position, new TaskStatus.Executing(percent));
            } else if (state instanceof ImageLoadState.Success) {
                File file = ((ImageLoadState.Success) state).getFile();

                Runnable showOriginal = () -> {
                    if (!originalUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) {
                        return;
                    }
                    showOriginalOverlay(holder, file, originalUrl, position);
                };

                if (DEBUG_DELAY_ORIGINAL) {
                    // 延迟显示，观察 large 稳定效果
                    new Handler(Looper.getMainLooper()).postDelayed(showOriginal, DEBUG_DELAY_MS);
                } else {
                    showOriginal.run();
                }

                reportPageStatus(position, TaskStatus.Finished.INSTANCE);
            } else if (state instanceof ImageLoadState.Error) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                holder.baseBind.reload.setVisibility(View.VISIBLE);
                Throwable cause = ((ImageLoadState.Error) state).getCause();
                Timber.w(cause, "[IllustAdapter] original load failed. pos=%d", position);
                reportPageStatus(position, new TaskStatus.Error(
                        (cause instanceof Exception) ? (Exception) cause : new Exception(cause)));
            }
        };

        task.getStateLiveData().observe(mFragment.getViewLifecycleOwner(), stateObserver);
        holder.itemView.setTag(R.id.tag_task_observers,
                (Runnable) () -> task.getStateLiveData().removeObserver(stateObserver));
    }

    /**
     * 显示原图 overlay（从 loadOriginalOverlay 中抽离出来）
     */
    private void showOriginalOverlay(ViewHolder<RecyIllustDetailBinding> holder, File file,
                                     String guardUrl, int position) {
        if (position == 0 && Shaft.sSettings.isShowOriginalPreviewImage()) {
            SketchPreloader.warm(mContext, file);
        }

        String shortUrl = guardUrl.substring(guardUrl.lastIndexOf('/') + 1);
        holder.baseBind.illustHd.setVisibility(View.INVISIBLE);

        Glide.with(mFragment)
                .asBitmap()
                .load(file)
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object m, Target<Bitmap> target, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        holder.baseBind.reload.setVisibility(View.VISIBLE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object m, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;

                        // ✅ 原图加载完成，只替换内容，不调整高度
                        Timber.d("[IllustAdapter] original overlay ready, replacing large. pos=%d, %dx%d",
                                position, resource.getWidth(), resource.getHeight());

                        holder.baseBind.reload.setVisibility(View.GONE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        holder.baseBind.illustHd.setVisibility(View.VISIBLE);
                        Shaft.getMMKV().encode(guardUrl, true);
                        return false;
                    }
                })
                .into(holder.baseBind.illustHd);
    }

    private void loadFromNetwork(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize) {
        boolean loadOriginal = Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal;
        final String largeUrl = IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_LARGE);
        final String targetUrl = loadOriginal
                ? IllustDownload.getUrl(allIllust, position, Params.IMAGE_RESOLUTION_ORIGINAL)
                : largeUrl;

        // tag = 本次 bind 想显示的「最终」url;所有回调据此判 stale,复用时旧回调自动变 no-op(#912)。
        holder.baseBind.illust.setTag(R.id.tag_image_url, targetUrl);
        holder.baseBind.reload.setVisibility(View.GONE);
        holder.baseBind.reload.setOnClickListener(v -> loadIllust(holder, position, changeSize));

        // 总是先用 large 秒显 —— 命中外面瀑布流 A 已加载的 Glide 内存/磁盘缓存(同 url 同 key,立即出图)。
        // 「仅 large」模式下它就是最终图;「原图」模式下它是即时占位,原图下好再覆盖上去。
        holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
        holder.baseBind.progressLayout.donutProgress.setProgress(0);
        renderBase(holder, position, changeSize, new GlideUrlChild(largeUrl), targetUrl, /*isFinal=*/!loadOriginal);

        if (!loadOriginal) {
            // 设置没开加载原图 → 停在 large:不建 imageloader 任务、不下原图。
            return;
        }

        // 设置开了 → 在 large 占位之上再加载原图(imageloader 共享任务,与大图页 C 复用同一次下载/进度/结果)。
        String shortUrl = targetUrl.substring(targetUrl.lastIndexOf('/') + 1);
        ImageLoadTask task = ImageLoaderV3.obtain(targetUrl);
        // 已失败的任务在(重新)绑定时强制重来一次(对齐旧 TaskPool「rebind 即重下」+ 让重试横幅生效)。
        if (task.getState().getValue() instanceof ImageLoadState.Error) {
            task.retry();
        }
        Timber.d("[IllustAdapter] loadIllust original pos=%d, state=%s, url=%s",
                position, task.getState().getValue(), shortUrl);

        final Observer<ImageLoadState> stateObserver = state -> {
            if (!targetUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) {
                return;
            }
            if (state instanceof ImageLoadState.Loading) {
                int percent = ((ImageLoadState.Loading) state).getPercent();
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
                holder.baseBind.progressLayout.donutProgress.setProgress(percent);
                reportPageStatus(position, new TaskStatus.Executing(percent));
            } else if (state instanceof ImageLoadState.Success) {
                // 原图就绪 → 加载进「顶层」illust_hd，就绪后淡入盖住底层 large(large 从不被清 → 零闪烁)。
                renderOverlay(holder, ((ImageLoadState.Success) state).getFile(), targetUrl, position);
                reportPageStatus(position, TaskStatus.Finished.INSTANCE);
            } else if (state instanceof ImageLoadState.Error) {
                holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                holder.baseBind.reload.setVisibility(View.VISIBLE);
                Throwable cause = ((ImageLoadState.Error) state).getCause();
                Timber.w(cause, "[IllustAdapter] original load failed, showing reload. pos=%d, url=%s", position, shortUrl);
                reportPageStatus(position, new TaskStatus.Error(
                        (cause instanceof Exception) ? (Exception) cause : new Exception(cause)));
            }
        };

        task.getStateLiveData().observe(mFragment.getViewLifecycleOwner(), stateObserver);
        holder.itemView.setTag(R.id.tag_task_observers,
                (Runnable) () -> task.getStateLiveData().removeObserver(stateObserver));
    }

    /**
     * large → 底层 {@code illust}(带动态 resize)。{@code isFinal=true}(仅 large 模式,large 即最终图)时,
     * 其成功/失败负责收进度环 / 亮重载按钮;{@code isFinal=false}(large 只是原图的占位)不碰进度环。
     * 回调按 {@code guardUrl}(=tag)判 stale,复用时自动 no-op。
     */
    private void renderBase(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize,
                            Object model, String guardUrl, boolean isFinal) {
        String shortUrl = guardUrl.substring(guardUrl.lastIndexOf('/') + 1);
        RequestManager requestManager = mFragment != null ? Glide.with(mFragment) : Glide.with(mContext);
        requestManager
                .asBitmap()
                .load(model)
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object m, Target<Bitmap> target, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.w(e, "[IllustAdapter] base(large) FAIL pos=%d, isFinal=%b, url=%s", position, isFinal, shortUrl);
                        if (isFinal) {
                            holder.baseBind.reload.setVisibility(View.VISIBLE);
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object m, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.d("[IllustAdapter] base(large) OK pos=%d, isFinal=%b, ds=%s, url=%s", position, isFinal, dataSource.name(), shortUrl);
                        holder.baseBind.reload.setVisibility(View.GONE);
                        if (isFinal) {
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        }
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));
    }

    /**
     * 原图 → 顶层 {@code illust_hd},就绪后设为可见 + crossfade 淡入,盖住底层 large。底层 large 全程不被清、
     * 不共享/回收 bitmap → 大图换原图零闪烁。尺寸随底层 illust(布局四边对齐)。原图失败则保留底层 large、亮重载。
     */
    private void renderOverlay(ViewHolder<RecyIllustDetailBinding> holder, File file, String guardUrl, int position) {
        // 仅对首图(pos 0,点进去最常打开的那张)且「详情展示原图」设置开启时,把原图预热进 Sketch 内存缓存,
        // 让二级大图页 C 首次打开秒开不黑屏。只挂 pos 0 是为避免翻多图时每页都多解一遍原图的性能开销。
        if (position == 0 && Shaft.sSettings.isShowOriginalPreviewImage()) {
            SketchPreloader.warm(mContext, file);
        }
        String shortUrl = guardUrl.substring(guardUrl.lastIndexOf('/') + 1);
        // 关键:先置 INVISIBLE(不是 GONE)。GONE 视图不参与 measure/layout,尺寸为 0,Glide 的 ViewTarget
        // 拿不到尺寸会一直等、onResourceReady 永不触发;INVISIBLE 照常布局(拿得到 illust 的尺寸)、只是不绘制,
        // 底层 large 依旧透出来。就绪后再置 VISIBLE 淡入盖上。
        holder.baseBind.illustHd.setVisibility(View.INVISIBLE);
        RequestManager requestManager = mFragment != null ? Glide.with(mFragment) : Glide.with(mContext);
        requestManager
                .asBitmap()
                .load(file)
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object m, Target<Bitmap> target, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.w(e, "[IllustAdapter] overlay(original) FAIL pos=%d, url=%s", position, shortUrl);
                        holder.baseBind.reload.setVisibility(View.VISIBLE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object m, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.d("[IllustAdapter] overlay(original) OK pos=%d, %dx%d, ds=%s, url=%s",
                                position, resource.getWidth(), resource.getHeight(), dataSource.name(), shortUrl);
                        holder.baseBind.reload.setVisibility(View.GONE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        holder.baseBind.illustHd.setVisibility(View.VISIBLE);
                        Shaft.getMMKV().encode(guardUrl, true);
                        return false;
                    }
                })
                .into(holder.baseBind.illustHd);
    }

    private void reportPageStatus(int position, @NonNull TaskStatus status) {
        if (pageStatusListener != null) {
            pageStatusListener.onStatusChanged(position, status);
        }
    }

    /**
     * 直接 Glide 读已下载的本地文件（content:// 或 file://），不走 LoadTask 网络下载。
     * 读失败（文件被移动/删除/无权限）就忘掉这页的本地映射、回退网络。observer 由
     * 上层 detachTaskObservers 统一清理，本路径不挂 LiveData observer，不会累积(#912)。
     */
    private void loadFromLocalFile(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize, Uri localUri) {
        boolean isLoadOriginalImage = Shaft.sSettings.isShowOriginalPreviewImage() || isForceOriginal;
        final String imageUrl = IllustDownload.getUrl(allIllust, position,
                isLoadOriginalImage ? Params.IMAGE_RESOLUTION_ORIGINAL : Params.IMAGE_RESOLUTION_LARGE);
        // 与网络路径一致地打 tag，让后续 stale 回调判定、reload 重试逻辑共用一套。
        holder.baseBind.illust.setTag(R.id.tag_image_url, imageUrl);
        holder.baseBind.reload.setVisibility(View.GONE);
        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
        holder.baseBind.reload.setOnClickListener(v -> loadIllust(holder, position, changeSize));

        RequestManager requestManager = mFragment != null ? Glide.with(mFragment) : Glide.with(mContext);
        requestManager
                .asBitmap()
                .load(localUri)
                .transform(new LargeBitmapScaleTransformer())
                .transition(BitmapTransitionOptions.withCrossFade())
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                        if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.w(e, "[IllustAdapter] local file load FAIL pos=%d, fall back to network", position);
                        localPageUris.remove(position);
                        // Glide forbids starting/clearing a load from inside a Target/RequestListener
                        // callback. loadFromNetwork() observes the per-URL task's *sticky* result
                        // LiveData, which can dispatch synchronously and call Glide.into() — that
                        // clears this very request while it's still in onLoadFailed and throws
                        // "You can't start or clear loads in ... callbacks". Defer to the next main-
                        // loop tick so this callback unwinds first.
                        holder.baseBind.illust.post(() -> {
                            // By the next tick the fragment's view may be gone (user navigated
                            // away). loadFromNetwork() reads mFragment.getViewLifecycleOwner(),
                            // which throws once getView()==null (after onDestroyView). Bail first.
                            if (mFragment == null || mFragment.getView() == null) return;
                            if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return;
                            loadIllust(holder, position, changeSize);
                        });
                        return true; // 已接管，网络路径会重新填图
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!imageUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        holder.baseBind.reload.setVisibility(View.GONE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, changeSize));
    }
}
