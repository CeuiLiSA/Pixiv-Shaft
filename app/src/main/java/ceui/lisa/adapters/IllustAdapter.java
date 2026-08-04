package ceui.lisa.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
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
import ceui.pixiv.download.RecordedPageProbe;
import ceui.pixiv.utils.SketchPreloader;
import ceui.pixiv.imageloader.ImageLoadState;
import ceui.pixiv.imageloader.ImageLoadTask;
import ceui.pixiv.imageloader.ImageLoaderV3;
import ceui.pixiv.ui.task.TaskStatus;

public class IllustAdapter extends AbstractIllustAdapter<ViewHolder<RecyIllustDetailBinding>> {

    /**
     * feed 里可能同时创建很多 adapter；每个实例各起一条 Thread 会在线程栈和调度上产生
     * 尖峰。两个低优先级 worker 足够覆盖 Room + SAF 的阻塞读取，也不会挤占图片解码。
     */
    private static final ExecutorService LOCAL_SCAN_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "illust-local-scan");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Reports per-page LoadTask status changes to the host (used by V3's retry-all banner). */
    public interface PageStatusListener {
        void onStatusChanged(int position, @NonNull TaskStatus status);
    }

    private final int maxHeight;
    private final FragmentActivity mActivity;
    private final Fragment mFragment;
    /**
     * 构造时 Fragment 一定已 attach，在这里拿到并复用其 RequestManager。View 销毁后的晚到
     * recycle 只允许 clear 旧请求，不能再调用 Glide.with(fragment) 重新检索——Fragment 已
     * detach 时后者会直接抛 IllegalArgumentException。
     */
    private final RequestManager fragmentRequestManager;
    private static final boolean longPressDownload = Shaft.sSettings.isIllustLongPressDownload();

    @Nullable
    private PageStatusListener pageStatusListener;
    @Nullable
    private Runnable localPagesChangedListener;
    private volatile boolean released = false;

    /**
     * 页码 -> 已下载文件 Uri。后台扫一次 illust_download_table，命中的页在绑定时
     * 直接 Glide 读本地文件，免得详情页(尤其多图「展开」后)又回 pixiv 重新下。
     * 见用户反馈：未展开时下载，展开后第二张及之后被重新加载。
     */
    private final Map<Integer, Uri> localPageUris = new ConcurrentHashMap<>();
    private volatile boolean localScanRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 页码 -> 该页展示 ratio(= 高/宽)。{@link ceui.lisa.view.DynamicHeightImageView} 在
     * {@code onMeasure} 里用真实测量宽 × ratio 算高,故 ratio 一确定,该页首帧就量在终值上,
     * 不再有「兜底高→自然高」的跳。ratio 三种来源,越早越好:
     * <ul>
     *   <li>P1:{@link IllustsBean#getWidth()}/{@code getHeight()},绑定即知;</li>
     *   <li>多 P 第 2 张起:{@link #seedPageDimensions} 用网页 ajax 的每页真尺寸提前预置;</li>
     *   <li>兜底(无 cookie / 精简 bean 无尺寸):图片解码后由 {@link #rememberDecodedRatio}
     *       用位图宽高定下。</li>
     * </ul>
     * 缓存进此表 → 滑走再回、回收重绑时首帧直接套用,消除抖动;{@link #release()} 时清空。
     */
    private final Map<Integer, Float> pageRatio = new ConcurrentHashMap<>();

    /**
     * 已用原图真尺寸校准过展示盒的页码。原图落盘后尺寸不变,故每页只需在 {@link #renderOverlay} 里
     * 「只读宽高」一次;{@code stateObserver} 的 sticky LiveData 会在每次 rebind 重投 Success → renderOverlay
     * 反复触发,若不去重就是每次回绑都在主线程重读一遍文件头。{@link #release()} 时清空。
     */
    private final Set<Integer> overlaySizedPages = ConcurrentHashMap.newKeySet();

    /**
     * 已成功显示过一次底图的页码,用于区分「首次显示 / 回收重绑」(#963):首显带 crossFade 淡入,
     * 避免深色模式下从暗占位硬切到亮图闪眼;回收重绑(滑走再滑回)则 dontAnimate 直接贴图,
     * 保住 d66956e3 消掉的「重绑从灰底淡入一闪」。{@link #release()} 时清空。
     */
    private final Set<Integer> shownPages = ConcurrentHashMap.newKeySet();

    public IllustAdapter(FragmentActivity activity, Fragment fragment, IllustsBean illustsBean, int maxHeight, boolean isForceOriginal) {
        Common.showLog("IllustAdapter maxHeight " + maxHeight);
        mActivity = activity;
        mContext = fragment.requireContext();
        allIllust = illustsBean;
        this.maxHeight = maxHeight;
        imageSize = mContext.getResources().getDisplayMetrics().widthPixels;
        this.isForceOriginal = isForceOriginal;
        this.mFragment = fragment;
        this.fragmentRequestManager = Glide.with(fragment);
        scanLocalDownloads();
    }

    public void setPageStatusListener(@Nullable PageStatusListener listener) {
        this.pageStatusListener = listener;
    }

    /**
     * delegate 模式（ArtworkV3 feeds）下本 adapter 没直接挂 RecyclerView，自己的
     * notifyDataSetChanged 不会让外层 FeedAdapter 重绑；由宿主通过此回调提交一次条目更新。
     */
    public void setLocalPagesChangedListener(@Nullable Runnable listener) {
        this.localPagesChangedListener = listener;
    }

    /** View 生命周期结束时断开回调，避免后台下载记录扫描把旧 Fragment/View 留到扫描完成。 */
    public void release() {
        released = true;
        pageStatusListener = null;
        localPagesChangedListener = null;
        pageRatio.clear();
        overlaySizedPages.clear();
        shownPages.clear();
        mainHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 后台扫描该作品已下载的页 → 本地文件 Uri。命中后回主线程刷新，绑定时优先走本地。
     * 调两次：构造时(覆盖「打开前就下好了」)、展开时(覆盖「未展开时下载，再展开」)。
     * 页码 → 文件名用 {@link FileCreator#customFileName}，与下载时写库的 fileName 同源，
     * 所以是精确的逐页匹配，不依赖文件名字典序，分图缺页也不会错位。
     */
    public void scanLocalDownloads() {
        final IllustsBean illust = allIllust;
        if (released || illust == null || localScanRunning || illust.isGif()) {
            return;
        }
        final int pageCount = Math.max(illust.getPage_count(), 1);
        final long illustId = illust.getId();
        localScanRunning = true;
        LOCAL_SCAN_EXECUTOR.execute(() -> {
            if (released) return;
            // 展开时会再扫一次，以发现“构造后新下载”的页。已经验过可读的页直接复用，
            // 避免多 P 作品重复 openFileDescriptor。
            final Map<Integer, Uri> found = new HashMap<>(localPageUris);
            try {
                DownloadDao dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao();

                // 先按 (illustId, page) 查（v41 复合索引）。这条路跟文件叫什么名字无关，
                // 所以用户换过命名模板、或记录是 DownloadImporter 从旧版命名的文件扫进来
                // 的（issue #953），照样命中。
                for (ceui.lisa.database.DownloadedPage e : dao.getDownloadedPages(illustId)) {
                    if (released) return;
                    if (e == null || e.filePath == null || e.filePath.isEmpty()) continue;
                    int page = e.page;
                    if (page < 0 || page >= pageCount || found.containsKey(page)) continue;
                    Uri usable = RecordedPageProbe.usableUri(mContext, e.filePath);
                    if (usable != null) {
                        // 查询按下载时间倒序；同一页有重复记录时保留第一条仍可打开的，
                        // 不让较新的孤儿行遮住仍完好的旧文件。
                        found.put(page, usable);
                    }
                }

                // 再用旧的 fileName 主键路补漏：v41 之前的存量行 page 还是 -1
                // （DownloadPageBackfill 没跑完 / 文件名解析不出页码）。只补上面没查到的页。
                final List<String> fileNames = new ArrayList<>(pageCount);
                final Map<String, Integer> pageByFileName = new HashMap<>(pageCount);
                for (int i = 0; i < pageCount; i++) {
                    if (released) return;
                    if (found.containsKey(i)) continue;
                    String fileName = FileCreator.customFileName(illust, i);
                    fileNames.add(fileName);
                    pageByFileName.put(fileName, i);
                }
                if (!fileNames.isEmpty()) {
                    // 单次 IN 查询取代 N 次 Room 调用。Pixiv 多 P 上限远低于 SQLite 变量上限。
                    for (DownloadEntity e : dao.getDownloadsByFileNames(fileNames)) {
                        if (released) return;
                        if (e != null && e.getFilePath() != null && !e.getFilePath().isEmpty()) {
                            Integer page = pageByFileName.get(e.getFileName());
                            Uri usable = RecordedPageProbe.usableUri(mContext, e.getFilePath());
                            if (page != null && usable != null) {
                                found.put(page, usable);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Timber.w(t, "[IllustAdapter] scanLocalDownloads failed, id=%d", illustId);
            }
            if (released) return;
            mainHandler.post(() -> {
                if (released) return;
                localScanRunning = false;
                boolean changed = false;
                for (Map.Entry<Integer, Uri> en : found.entrySet()) {
                    if (localPageUris.put(en.getKey(), en.getValue()) == null) {
                        changed = true;
                    }
                }
                if (changed) {
                    notifyDataSetChanged();
                    Runnable listener = localPagesChangedListener;
                    if (listener != null) listener.run();
                }
            });
        });
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
        fragmentRequestManager.clear(holder.baseBind.illust);
        fragmentRequestManager.clear(holder.baseBind.illustHd);
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

        // 统一 FIT_CENTER:有了每页真 ratio,展示盒与图同比,不letterbox也不裁切(旧代码后续页用
        // CENTER_CROP 只是为掩盖「高度靠解码后才定」的临时错比,现在不需要)。
        holder.baseBind.illust.setScaleType(ImageView.ScaleType.FIT_CENTER);

        boolean changeSize; // = ratio 未知,需等图解码后由 rememberDecodedRatio 定 ratio
        if (position == 0 && allIllust.getWidth() > 0 && allIllust.getHeight() > 0) {
            // 第一张:宽高来自 IllustsBean。单 P 扁图垫 maxHeight 居中、其余按真 ratio(见 applyPixelSize)。
            applyPixelSize(holder, position, allIllust.getWidth(), allIllust.getHeight());
            changeSize = false;
        } else {
            // 多 P 第 2 张起:ratio 已知(网页 ajax 预置 / 上次解码缓存)→ 首帧就量在终值;未知 → 占位高,
            // 等图解码后 rememberDecodedRatio 定 ratio。DynamicHeightImageView 用真实宽算高,回收重绑不抖。
            changeSize = applyRatioOrPlaceholder(holder, position);
        }

        Timber.tag("V3MultiP").d(
            "[IllustAdapter.bind pos=%d] illustId=%d, page_count=%d, ratio=%s, changeSize=%b, adapterClass=%s",
            position, allIllust.getId(), allIllust.getPage_count(), pageRatio.get(position),
            changeSize, this.getClass().getSimpleName()
        );
        loadIllust(holder, position, changeSize);
    }

    /**
     * ratio 已知就套用(首帧即终值),未知就退回占位高({@code maxHeight} 或布局默认)并返回
     * {@code changeSize=true} —— 让 {@link #loadIllust} 走「解码后定 ratio」的兜底路径。
     * @return changeSize:true 表示 ratio 未知、需解码后定。
     */
    private boolean applyRatioOrPlaceholder(ViewHolder<RecyIllustDetailBinding> holder, int position) {
        Float ratio = pageRatio.get(position);
        if (ratio != null && ratio > 0f) {
            holder.baseBind.illust.setHeightRatio(ratio);
            return false;
        }
        int placeholder = maxHeight > 0 ? maxHeight : holder.baseBind.illust.getLayoutParams().height;
        setFixedHeight(holder, placeholder);
        return true;
    }

    /** 关掉 ratio 自measure、改用固定像素高(扁图垫底 / 尺寸未知的占位)。FIT_CENTER 居中不裁。 */
    private void setFixedHeight(ViewHolder<RecyIllustDetailBinding> holder, int height) {
        holder.baseBind.illust.setHeightRatio(0f);
        ViewGroup.LayoutParams params = holder.baseBind.illust.getLayoutParams();
        if (params != null && height > 0 && params.height != height) {
            params.height = height;
            holder.baseBind.illust.setLayoutParams(params);
        }
    }

    /**
     * 按该页真实像素宽高定展示盒,套到底层 {@code illust} 与顶层 {@code illust_hd}(布局四边对齐 illust、
     * 同盒同比):pos0 单 P 扁图(自然高 < maxHeight)垫 maxHeight 居中,其余按真 ratio(高/宽)并存
     * {@link #pageRatio} 供回收重绑首帧直接用。两处调用同一口径:绑定时用 {@link IllustsBean} 的宽高;
     * {@link #renderOverlay} 贴原图前用「只读宽高」解码出的原图真尺寸再校准一次。
     */
    private void applyPixelSize(ViewHolder<RecyIllustDetailBinding> holder, int position, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        float ratio = (float) h / w;
        int naturalHeight = Math.round((float) imageSize * ratio);
        if (position == 0 && allIllust.getPage_count() == 1 && maxHeight > 0 && naturalHeight < maxHeight) {
            setFixedHeight(holder, maxHeight);
            holder.baseBind.illustHd.setHeightRatio(0f);
        } else {
            holder.baseBind.illust.setHeightRatio(ratio);
            holder.baseBind.illustHd.setHeightRatio(ratio);
            pageRatio.put(position, ratio);
        }
    }

    /**
     * 只读文件头拿宽高、不分配像素({@link BitmapFactory.Options#inJustDecodeBounds}=true,几乎零开销)。
     * 返回 {@code {width, height}};读不出(文件缺失 / 非图 / 已损坏)返回 null。
     */
    @Nullable
    private static int[] readImageBounds(@Nullable File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        return new int[]{options.outWidth, options.outHeight};
    }

    /**
     * @param holder
     * @param position
     * @param changeSize 是否自动计算宽高
     */
    private void loadIllust(ViewHolder<RecyIllustDetailBinding> holder, int position, boolean changeSize) {
        // Drop observers left over from a previous bind of this recycled holder before
        // registering new ones. Prevents unbounded observer accumulation across rebinds. #912
        detachTaskObservers(holder);

        // 复用前重置「顶层原图」overlay，避免上一条的原图盖在这次的图上。底层 large 由各渲染路径自行覆盖。
        // 走构造期就兑现好的 fragmentRequestManager，别在这里 Glide.with(mFragment)：那条重载会
        // requireNonNull(fragment.getContext())，绑卡若赶在 fragment 已 detach 时打进来就是 NPE。
        fragmentRequestManager.clear(holder.baseBind.illustHd);
        holder.baseBind.illustHd.setImageDrawable(null);
        holder.baseBind.illustHd.setVisibility(View.GONE);

        // 命中已下载的本地文件就直读，跳过网络 LoadTask —— 详情页展开多图复用下载结果。
        Uri localUri = localPageUris.get(position);
        if (localUri != null) {
            loadFromLocalFile(holder, position, changeSize, localUri);
            return;
        }
        loadFromNetwork(holder, position, changeSize);
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

        holder.baseBind.progressLayout.donutProgress.setVisibility(View.VISIBLE);
        holder.baseBind.progressLayout.donutProgress.setProgress(0);

        if (!loadOriginal) {
            // 仅 large 模式:large 就是最终图,每页都要真正下下来展示。
            renderBase(holder, position, changeSize, new GlideUrlChild(largeUrl), targetUrl, /*isFinal=*/true);
            return;
        }

        // 原图模式:large 只是占位,且【只有首图 pos 0】的 large 被外面瀑布流预热进缓存、能真正秒显。
        // 展开的多 P 页(pos>0)的 large 从没在列表出现过、不在缓存,拿它当占位既不能秒显、下好又被原图
        // 立刻盖掉 → 白下一轮 large,纯浪费流量与时延。所以多 P 一律【不发 large 请求】,直接等原图。
        // 连 pos 0 也在 renderBase 里走 onlyRetrieveFromCache:真命中才秒显,极少见的没命中(如 deeplink
        // 直进详情、large 未预热)也直接等原图、不白下 large。
        if (position == 0) {
            renderBase(holder, position, changeSize, new GlideUrlChild(largeUrl), targetUrl, /*isFinal=*/false);
        }

        // 设置开了 → 加载原图(imageloader 共享任务,与大图页 C 复用同一次下载/进度/结果):pos 0 盖在
        // large 占位之上,多 P 则直接盖在灰底占位上。原图下好淡入 illust_hd,底层从不被清 → 零闪烁。
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
        // 首显 crossFade、重绑 dontAnimate(#963):首次显示从深色占位淡入,避免暗环境下亮图硬切闪眼;
        // 滑走再滑回的回收重绑(shownPages 已记录)直接贴图,保住 d66956e3 消掉的「重绑从灰底淡入一闪」。
        // 内存缓存命中时 Glide 的 crossFade 工厂本就返回 NoTransition(同帧出图、无闪),秒显不受影响。
        boolean firstShow = !shownPages.contains(position);
        RequestBuilder<Bitmap> request = requestManager
                .asBitmap()
                .load(model)
                .transform(new LargeBitmapScaleTransformer())
                // isFinal=false 时 large 只是原图占位(调用点已保证仅 pos 0 才走到这里,多 P 根本不发 large)
                // → onlyRetrieveFromCache:只在 large 已被外面瀑布流预热进内存/磁盘缓存时秒显,没命中就立即
                // 失败、不为占位单独走网络下 large(兜住「pos 0 但 large 未预热」的极少数入口)。
                // isFinal=true(仅 large 模式,large 就是最终图)→ 允许走网络,必须真正下下来展示。
                .onlyRetrieveFromCache(!isFinal);
        request = firstShow
                ? request.transition(BitmapTransitionOptions.withCrossFade())
                : request.dontAnimate();
        request
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object m, Target<Bitmap> target, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        if (isFinal) {
                            Timber.w(e, "[IllustAdapter] base(large) FAIL pos=%d, url=%s", position, shortUrl);
                            holder.baseBind.reload.setVisibility(View.VISIBLE);
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        } else {
                            // 占位 large 缓存未命中是预期路径(原图正在下、下好即盖上),不算错误、不亮重载、不动进度环。
                            Timber.d("[IllustAdapter] base(large) cache-miss placeholder skipped pos=%d, url=%s", position, shortUrl);
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Bitmap resource, Object m, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                        if (!guardUrl.equals(holder.baseBind.illust.getTag(R.id.tag_image_url))) return false;
                        Timber.d("[IllustAdapter] base(large) OK pos=%d, isFinal=%b, ds=%s, url=%s", position, isFinal, dataSource.name(), shortUrl);
                        shownPages.add(position);
                        holder.baseBind.reload.setVisibility(View.GONE);
                        if (isFinal) {
                            holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        }
                        rememberDecodedRatio(holder, position, changeSize, resource);
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, false));
    }

    /**
     * 兜底路径:该页 ratio 事先未知(无网页 ajax 尺寸、精简 bean 无有效 meta 宽高)时,图片解码完成后
     * 用位图宽高定下并缓存该页展示 ratio,并即时套到 {@link ceui.lisa.view.DynamicHeightImageView}。
     * 缓存进 {@link #pageRatio} → 回收重绑首帧直接套用,不再「占位高→自然高」跳。仅 {@code changeSize}
     * (ratio 未知)页生效;ratio 已知的页高度绑定时就摆正,不进此路径。
     */
    private void rememberDecodedRatio(ViewHolder<RecyIllustDetailBinding> holder, int position,
                                      boolean changeSize, Bitmap resource) {
        if (!changeSize || resource == null || resource.getWidth() <= 0 || resource.getHeight() <= 0) {
            return;
        }
        float ratio = (float) resource.getHeight() / resource.getWidth();
        if (ratio > 0f) {
            pageRatio.put(position, ratio);
            holder.baseBind.illust.setHeightRatio(ratio);
        }
    }

    /**
     * 用网页 ajax {@code /ajax/illust/{id}/pages} 拿到的「每 P 真实原图宽高」预置各页展示 ratio。
     * <p>多 P 第 2 张起 ratio 本来要等图片解码完才由 {@link #rememberDecodedRatio} 定(先按占位高
     * 布局、图一到再跳);这里提前把真尺寸换算成 ratio(高/宽)塞进 {@link #pageRatio}。折叠页展开 /
     * 后续页首次绑定时,{@link ceui.lisa.view.DynamicHeightImageView} 用真实宽 × ratio 算高,首帧就量
     * 在终值上,消除那一跳。ratio 与解码兜底同一套口径,不会二次跳。
     * <p>{@code dims} 按页序:{@code dims.get(i) = {width, height}} 对应第 i 页。cookie 缺失 / 接口
     * 失败时上层根本不会调用它 → 保持解码后定 ratio 的兜底行为。<b>静默写表、不 notify</b>:已在屏的页
     * 保留各自的解码兜底(强行 rebind 会闪),尚未绑定的页(折叠 3P+ 展开、上滑新上屏)自然读表命中——
     * 正是首帧跳最明显的场景。
     */
    public void seedPageDimensions(@Nullable List<int[]> dims) {
        if (released || dims == null || dims.isEmpty()) {
            return;
        }
        for (int i = 0; i < dims.size(); i++) {
            int[] wh = dims.get(i);
            if (wh == null || wh.length < 2) {
                continue;
            }
            int w = wh[0];
            int h = wh[1];
            if (w <= 0 || h <= 0) {
                continue;
            }
            pageRatio.put(i, (float) h / w);
        }
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
        // 真正贴图前:首次为该页拿原图真尺寸(bounds-only 解码,不分配像素、只读文件头,几乎零开销),
        // 先把展示盒 ratio 同步到原图真比(比 metadata / 网页 ajax 更准;setHeightRatio 内部判等,未变不 relayout),
        // 再淡入原图 → 顶层 illust_hd 与底层 illust 同盒同比,crossfade 盖上时零 letterbox、零跳。
        // 原图落盘后尺寸不变 → 每页只读一次,避免 sticky LiveData 每次 rebind 都在主线程重读文件头。
        if (overlaySizedPages.add(position)) {
            int[] bounds = readImageBounds(file);
            if (bounds != null) {
                applyPixelSize(holder, position, bounds[0], bounds[1]);
            }
        }
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
        // 与 renderBase 同一口径(#963):首显 crossFade 淡入(硬盘直读等待期间眼睛已适应暗底,
        // 硬切最刺眼的正是本路径),回收重绑 dontAnimate 直接贴图不从灰底淡入。
        boolean firstShow = !shownPages.contains(position);
        RequestBuilder<Bitmap> request = requestManager
                .asBitmap()
                .load(localUri)
                .transform(new LargeBitmapScaleTransformer());
        request = firstShow
                ? request.transition(BitmapTransitionOptions.withCrossFade())
                : request.dontAnimate();
        request
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
                        shownPages.add(position);
                        holder.baseBind.reload.setVisibility(View.GONE);
                        holder.baseBind.progressLayout.donutProgress.setVisibility(View.GONE);
                        rememberDecodedRatio(holder, position, changeSize, resource);
                        return false;
                    }
                })
                .into(new UniformScaleTransformation(holder.baseBind.illust, false));
    }
}
