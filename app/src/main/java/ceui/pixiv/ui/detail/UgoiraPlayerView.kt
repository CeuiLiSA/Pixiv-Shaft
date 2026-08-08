package ceui.pixiv.ui.detail

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import ceui.lisa.utils.V3Palette
import com.google.android.material.progressindicator.CircularProgressIndicator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.activities.Shaft
import ceui.lisa.R
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.ui.bulk.UGOIRA_LOG_TAG
import ceui.pixiv.ui.bulk.UgoiraEngine
import ceui.pixiv.ui.bulk.UgoiraPhase
import ceui.pixiv.ui.bulk.UgoiraProgress
import ceui.pixiv.utils.ppppx
import ceui.pixiv.ui.bulk.UgoiraFrames
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 详情页内联 ugoira 播放 View —— 预览图打底 + 进度浮层([UgoiraEngine] 出片后
 * Glide `asGif` 原地播放,出错显示「重试」)。
 *
 * 慢阶段有真实进度:zip 下载走字节级 %,GIF 编码走帧级 %,浮层用**确定圆环(复用 item_progress)
 * + 百分比**显示,进度直接跳值、不做补间动画;meta/解压很快,环停在当前进度即可。文案复用现成
 * 本地化串(下载=正在下载 / 其余=加载中…),不新增字符串。
 *
 * 完全不认识 Fragment;生命周期靠 [bind] 传入的 [LifecycleOwner](详情页传
 * viewLifecycleOwner),协程挂它的 lifecycleScope,页面销毁自动取消。
 */
class UgoiraPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    // 主题色板(日夜双模):重试胶囊取当前主题色,和详情页「关注 / 下载」等 V3 按钮同源。
    private val palette = V3Palette.from(context)

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    // 构造时拿一次 RequestManager；回收可能发生在 View 已 detach 之后，此时再 Glide.with(view)
    // 会重复做 ViewTree/lifecycle 查找，极端销毁时序下还可能命中已销毁 Activity。
    private val glide = Glide.with(context)

    // 进度环复用 item_progress(与插画列表加载圈同款 donut 环,Material CircularProgressIndicator)。
    // determinate,靠 plain setProgress 直接跳到目标值,**不带进度变化的补间动画**(用户要求);
    // 不再用 Material 1.14 的波浪条,库已降回 1.12。inflate 复用 item_progress.xml,样式(白色/30dp/
    // 圆角轨道)与列表加载圈完全一致,不再重复写一份。
    private val progressBar = LayoutInflater.from(context)
        .inflate(R.layout.item_progress, this, false) as CircularProgressIndicator
    private val captionText = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1 // 单行:换阶段文案时高度不跳
    }
    private val overlay = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(20.ppppx, 14.ppppx, 20.ppppx, 14.ppppx)
        // 固定最小宽度:各阶段文案(加载中… / 正在下载 NN% / 压制中 NN%)和 % 位数变化都在此宽度内,
        // 黑框不再随文字长短忽大忽小地闪动。文案居中,短文案留白也比一直变宽好。
        minimumWidth = 180.ppppx
        background = GradientDrawable().apply {
            cornerRadius = 12.ppppx.toFloat()
            setColor(0xB3000000.toInt()) // ~70% 黑,任何底图上都读得清
        }
        addView(
            progressBar,
            // 圆环:wrap x wrap 居中,按 indicatorSize(30dp)量尺寸。
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            captionText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.ppppx },
        )
        isVisible = false
    }

    // 「重试」按钮 V3 重设计:主题实心圆角胶囊 + 刷新图标 + 文案。白色前景在任意底图与日夜模式下都读得清,
    // 与详情页「关注 / 下载」主按钮同一套视觉语言,取代原先白字压半透明黑方块的粗糙样式。
    private val retryButton = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(18.ppppx, 9.ppppx, 20.ppppx, 9.ppppx)
        isClickable = true
        isFocusable = true
        isVisible = false
        background = buildRetryPill()
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_refresh_48)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            },
            LinearLayout.LayoutParams(18.ppppx, 16.ppppx).apply { marginEnd = 7.ppppx },
        )
        addView(
            TextView(context).apply {
                setText(R.string.retry)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.02f
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    // 「开始播放（下载）」按钮：关闭「动图自动播放」时显示在图片中间，点击才拉数据 →
    // 下载 → 解压 → 编码 → 播放。样式与「重试」按钮同一套视觉语言。
    private val playButton = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(18.ppppx, 9.ppppx, 20.ppppx, 9.ppppx)
        isClickable = true
        isFocusable = true
        isVisible = false
        background = buildRetryPill()
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_play_arrow_24)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            },
            LinearLayout.LayoutParams(18.ppppx, 16.ppppx).apply { marginEnd = 7.ppppx },
        )
        addView(
            TextView(context).apply {
                setText(R.string.ugoira_start_play_button)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.02f
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** 主题实心圆角胶囊 + 白色按压波纹(裁到胶囊形状)。 */
    private fun buildRetryPill(): Drawable {
        val radius = 999f
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(
            ColorStateList.valueOf(V3Palette.withAlpha(0xFFFFFFFF.toInt(), 0.28f)),
            palette.pillPrimary(radius),
            mask,
        )
    }

    /** 收浮层。Material 进度条会随聚合可见性自动停/续内部动画,浮层 GONE 即暂停,无需手动停。 */
    private fun hideOverlay() {
        overlay.isVisible = false
    }

    /**
     * 打底预览图：先用列表页大概率已缓存的 medium 秒出，再让 large 异步覆盖，
     * 避免进详情页时 large 首次网络下载导致的长时间空白。
     */
    private fun loadPreview(illust: IllustsBean) {
        if (previewIllustId == illust.id && imageView.drawable != null) return
        previewIllustId = illust.id
        val large = GlideUtil.getLargeImage(illust) ?: return
        val mediumUrl = illust.image_urls?.medium?.takeIf { it.isNotBlank() }
        glide.clear(imageView)

        var builder = glide.load(large)
        if (mediumUrl != null) {
            // 有 medium：它大概率已在列表缓存，秒出打底；large 到位后直接替换，不做淡入。
            builder = builder.thumbnail(glide.load(GlideUrlChild(mediumUrl)))
            builder.into(imageView)
        } else {
            // 没有 medium：等 large 从网络/硬盘到达后淡入。
            builder
                .transition(DrawableTransitionOptions.withCrossFade(300))
                .into(imageView)
        }
    }

    /** 关闭自动播放时按钮直接显隐，不做淡入——左右切入不会出现动画闪烁。 */
    private fun showPlayButton() {
        playButton.isVisible = true
    }

    private fun hidePlayButton() {
        playButton.isVisible = false
    }

    private var job: Job? = null
    private var player: FrameSequencePlayer? = null
    private var playingDir: String? = null
    private var playbackActive = false
    /** 点过播放且帧序列还没就绪（下载/解压/压制中）。切走再切回时继续显示进度、不弹按钮。 */
    private var downloadInFlight = false

    /** 已为哪版帧序列自动自愈过(dir path)。同一版自愈一次仍坏就转手动重试,不无限循环重下。 */
    private var autoHealedDir: String? = null
    private var boundOwner: LifecycleOwner? = null
    private var boundIllust: IllustsBean? = null
    /** 当前已加载/在加载的预览图对应的作品 id，用于重进去重。 */
    private var previewIllustId: Int? = null
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            resumePlayback()
        }

        override fun onPause(owner: LifecycleOwner) {
            pausePlayback()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            recycle()
        }
    }

    init {
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            overlay,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addView(
            retryButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addView(
            playButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    /** 绑定一条 ugoira。进入即自动拉数据 → 下载 → 解压 → 编码 → 播放。 */
    fun bind(owner: LifecycleOwner, illust: IllustsBean, maxHeight: Int) {
        if (boundIllust?.id != illust.id) {
            pausePlayback()
            // 换了一条作品：旧的预览/帧画面不能残留，等新预览就位。
            downloadInFlight = false
            hideOverlay()
            previewIllustId = null
            glide.clear(imageView)
            imageView.setImageDrawable(null)
        }
        if (boundOwner !== owner) {
            boundOwner?.lifecycle?.removeObserver(lifecycleObserver)
            boundOwner = owner
            owner.lifecycle.addObserver(lifecycleObserver)
        }
        boundIllust = illust
        // 高度按原图宽高比 * 屏宽,封顶 maxHeight —— 和 IllustAdapter / 老 ugora 页一致。
        val w = illust.width.coerceAtLeast(1)
        val h = illust.height.coerceAtLeast(1)
        val screenW = resources.displayMetrics.widthPixels
        var targetH = (screenW.toLong() * h / w).toInt()
        if (maxHeight > 0 && targetH > maxHeight) targetH = maxHeight
        imageView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, targetH)

        // 预览图打底(拿到 gif 前先显示首帧/大图)。同一 holder 的无关重绑不能把正在播的 GIF
        // 又替换回预览图。
        if (!playbackActive) loadPreview(illust)
        // 关闭自动播放时，按钮在预加载（view 创建）阶段就显示，不依赖页面激活；
        // 左右切入时按钮跟着页面一起进来，不会中途闪现；无论是否点过播放都显示。
        if (!playbackActive && !Shaft.sSettings.isAutoPlayUgoira) {
            showPlayButton()
        }

        Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d bind %dx%d", illust.id, illust.width, illust.height)
        retryButton.setOnClickListener {
            pausePlayback()
            startLoad(owner, illust)
        }
        playButton.setOnClickListener {
            pausePlayback()
            startLoad(owner, illust)
        }
        resumePlayback()
    }

    /** RecyclerView attach/detach 比 recycle 更及时：刚滚出屏幕就停，不等 holder 进回收池。 */
    fun onFeedAttached() = resumePlayback()

    fun onFeedDetached() = pausePlayback()

    /**
     * RecyclerView 回收时立即停止本 View 的收集与 Glide GIF。底层 UgoiraEngine 的共享任务按设计
     * 继续完成并落缓存，但已离屏的 View 不再持有 owner/bitmap，也不再消耗 GIF 解码帧。
     */
    fun recycle() {
        pausePlayback()
        boundOwner?.lifecycle?.removeObserver(lifecycleObserver)
        boundOwner = null
        boundIllust = null
        autoHealedDir = null
        retryButton.setOnClickListener(null)
        retryButton.isVisible = false
        playButton.setOnClickListener(null)
        playButton.isVisible = false
        downloadInFlight = false
        hideOverlay()
        previewIllustId = null
        glide.clear(imageView)
        imageView.setImageDrawable(null)
    }

    private fun pausePlayback() {
        playbackActive = false
        job?.cancel()
        job = null
        player?.stop()
        player = null
        playingDir = null
        // 不在这里清 ImageView；开关关闭时：下载/压制中保留进度浮层，否则把按钮放回来，
        // 左右切回秒显、无弹跳；真正释放放在 recycle()（holder 进回收池）再做。
        if (!Shaft.sSettings.isAutoPlayUgoira) {
            if (downloadInFlight) {
                // 保持进度浮层可见，切回继续显示进度，不弹按钮。
            } else {
                showPlayButton()
                hideOverlay()
            }
        } else {
            hideOverlay()
        }
    }

    private fun resumePlayback() {
        val owner = boundOwner ?: return
        val illust = boundIllust ?: return
        if (playbackActive) return
        if (!isAttachedToWindow ||
            !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        loadPreview(illust)
        // 关闭「动图自动播放」时：只打底预览图并显示中间按钮，任何路径（进页/左右切回/
        // 滚回屏幕/已缓存秒开）都不自动拉数据或播放，只有点按钮才 startLoad。
        if (!Shaft.sSettings.isAutoPlayUgoira) {
            if (downloadInFlight) {
                // 之前点过播放、下载还在进行：切回继续走下载→播放，显示进度。
                startLoad(owner, illust)
            } else {
                showPlayButton()
            }
            return
        }
        startLoad(owner, illust)
    }

    private fun startLoad(owner: LifecycleOwner, illust: IllustsBean) {
        playbackActive = true
        downloadInFlight = true
        job?.cancel()
        hidePlayButton()
        retryButton.isVisible = false
        // 秒开:内存已有帧序列就立刻播,不等协程、不显示浮层(主线程零 IO,不碰文件系统)。
        val ready = UgoiraEngine.peekReadyInMemory(illust.id)
        if (ready != null) {
            hideOverlay()
            Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d 内存秒开 %s", illust.id, ready.dir.name)
            playFrames(illust, ready)
        } else {
            // 起步进度归零。donut 环是 determinate,plain setProgress 直接置 0、无补间动画。
            progressBar.progress = 0
            overlay.isVisible = true
        }
        Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d startLoad", illust.id)
        job = owner.lifecycleScope.launch {
            // 观察引擎共享进度:再进来立刻拿到当前阶段。子协程,页面退出/播放完成随之取消。
            val progressJob = launch {
                UgoiraEngine.progressOf(illust.id).collect { renderProgress(it) }
            }
            // 帧序列就绪即开播。引擎只在最终版(补帧完成或确定不补)才发布,所以这里收到的
            // 就是最终帧率 —— 补帧期间画面保持预览图 + 进度浮层,不会先动一段低帧率的。
            val framesJob = launch {
                UgoiraEngine.framesOf(illust.id).collect { frames ->
                    if (frames != null) {
                        progressJob.cancel()
                        hideOverlay()
                        playFrames(illust, frames)
                    }
                }
            }
            try {
                // await 被取消(页面退出)不会取消底层任务——它继续在引擎 scope 跑完落缓存。
                val frames = UgoiraEngine.loadPlayableFrames(illust)
                progressJob.cancel()
                hideOverlay()
                // 兜底直接播 await 结果:加载中途 invalidate/invalidateAll(切补帧开关、清缓存)
                // 会把 framesFlows 里的条目换新,引擎 publish 到新 flow,上面 framesJob 订阅的
                // 旧 flow 永远收不到 —— 不兜这一手画面就冻在预览图上且无重试按钮。
                // playFrames 按 dir 去重,framesJob 已播过同一版时这里是 no-op。
                playFrames(illust, frames)
            } catch (c: CancellationException) {
                Timber.tag(UGOIRA_LOG_TAG).i("[player] illust=%d 协程取消(页面退出/重绑),底层任务继续在后台跑", illust.id)
                throw c
            } catch (t: Throwable) {
                progressJob.cancel()
                framesJob.cancel()
                // 已经播上原速版了就别退回「重试」——补帧失败不该毁掉能看的画面
                if (player == null) {
                    playbackActive = false
                    downloadInFlight = false
                    hideOverlay()
                    retryButton.isVisible = true
                }
                Timber.tag(UGOIRA_LOG_TAG).w(t, "[player] illust=%d 加载失败(已在播=%b)", illust.id, player != null)
            }
        }
    }

    /**
     * 换上一版帧序列开播。同一版重复回调直接忽略(StateFlow 会重放当前值);换版时旧播放器
     * stop、新播放器 start —— 两版一圈时长相同,视觉上接得住。
     */
    private fun playFrames(illust: IllustsBean, frames: UgoiraFrames) {
        if (playingDir == frames.dir.path) return
        if (frames.files.isEmpty()) return
        downloadInFlight = false
        player?.stop()
        playingDir = frames.dir.path
        retryButton.isVisible = false
        player = FrameSequencePlayer(
            frames.files,
            frames.delaysMs,
            onFrame = { bmp -> imageView.setImageBitmap(bmp) },
            onError = { onPlaybackBroken(illust, frames) },
        ).also { it.start() }
        Timber.tag(UGOIRA_LOG_TAG).i(
            "[player] illust=%d 开播 %s (%d帧, 一圈%dms, 补帧=%b)",
            illust.id, frames.dir.name, frames.files.size, frames.totalMs, frames.interpolated,
        )
    }

    /**
     * 播放器报「连续解码失败 / 解码线程异常」:帧文件多半被系统清缓存删了,而引擎内存缓存
     * 还指着死文件 —— 不处理的话画面永久冻结,连重试按钮都救不回来([startLoad] 会先命中
     * `peekReadyInMemory` 拿到同一批死文件)。清掉引擎内存记录、**并删掉盘上这版可疑目录**
     * 后自动重走一次完整 pipeline(否则「文件都在但内容坏」时磁盘命中同一批坏文件,自愈
     * 空转);同一版自愈过一次仍坏就只亮重试按钮,不无限循环烧流量。
     */
    private fun onPlaybackBroken(illust: IllustsBean, frames: UgoiraFrames) {
        if (playingDir != frames.dir.path) return // 过期回调(已换版/已停),不动现场
        UgoiraEngine.invalidate(illust.id)
        val healedBefore = autoHealedDir == frames.dir.path
        pausePlayback()
        if (healedBefore) {
            Timber.tag(UGOIRA_LOG_TAG).w("[player] illust=%d 自愈后仍解码失败,转手动重试", illust.id)
            // pausePlayback 会按开关把播放按钮放回来，这里只留「重试」，避免两个按钮叠一起。
            hidePlayButton()
            retryButton.isVisible = true
        } else {
            autoHealedDir = frames.dir.path
            Timber.tag(UGOIRA_LOG_TAG).w("[player] illust=%d 帧解码失败,清缓存后自动重建", illust.id)
            val owner = boundOwner ?: return
            owner.lifecycleScope.launch {
                // 先删再 resume,保证重建的 pipeline 不会磁盘命中这批坏文件。
                withContext(Dispatchers.IO) {
                    runCatching { UgoiraEngine.purgeBrokenFrames(illust.id, frames.dir) }
                }
                resumePlayback()
            }
        }
    }

    private fun renderProgress(p: UgoiraProgress) {
        val pct = p.percent
        if (pct != null) {
            // 平铺 setProgress:直接跳到目标值,不做进度变化的补间动画(用户要求)。
            progressBar.progress = pct
        }
        // 无 % 的阶段(FETCH_META / EXTRACT)保持当前进度,只更文案。
        // 本地化文案(7 语言均已补):下载=正在下载,编码=压制中(ugoira_encoding),
        // 其余(meta/解压)=加载中…;% 直接拼数字。
        val base = when (p.phase) {
            UgoiraPhase.DOWNLOAD_ZIP -> context.getString(R.string.now_downloading)
            UgoiraPhase.INTERPOLATE -> context.getString(R.string.ugoira_interpolating)
            UgoiraPhase.ENCODE -> context.getString(R.string.ugoira_encoding)
            else -> context.getString(R.string.now_loading)
        }
        captionText.text = if (pct != null) "$base  $pct%" else base
    }
}

/**
 * 单条目 adapter,把 [UgoiraPlayerView] 塞进详情页的 RecyclerView 图片位。
 * FragmentIllust(LinearLayoutManager)直接 setAdapter;ArtworkV3(ConcatAdapter +
 * StaggeredGridLayoutManager)addAdapter(0, this),靠 [onViewAttachedToWindow] 占满整行。
 */
class UgoiraPlayerAdapter(
    private val illust: IllustsBean,
    private val owner: LifecycleOwner,
    private val maxHeight: Int,
) : RecyclerView.Adapter<UgoiraPlayerAdapter.VH>() {

    class VH(val player: UgoiraPlayerView) : RecyclerView.ViewHolder(player)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val player = UgoiraPlayerView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return VH(player)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.player.bind(owner, illust, maxHeight)
    }

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
            lp.isFullSpan = true
            holder.itemView.layoutParams = lp
        }
        holder.player.onFeedAttached()
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        holder.player.onFeedDetached()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: VH) {
        holder.player.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = 1
}
