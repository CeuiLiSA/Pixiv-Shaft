package ceui.pixiv.ui.detail.frames

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.SeamlessCircularProgressIndicator
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.bulk.UgoiraPhase
import ceui.pixiv.ui.bulk.UgoiraProgress
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.setTextWithIcon
import ceui.pixiv.witstudio.theme.shape
import com.bumptech.glide.Glide
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import com.google.android.material.snackbar.Snackbar
import com.hjq.toast.Toaster
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 动图逐帧：把动图当成一段素材来「剪」，从里面挑出想要的那一帧存下来。
 *
 * 交互取几家剪辑 / 实况照片编辑器之长，落到 V3 的语言里：
 * - **剪映 / CapCut**：固定播放头、按帧时长铺开的胶片时间轴、双指缩放时间轴、刻度尺与时间码；
 * - **iOS 实况照片**：当前帧套一只选框，舞台上是这一帧的完整画面，可双指放大看细节；
 * - **Google 相册「导出帧」**：拖动松手吸附到帧、逐帧震动、原画质导出；
 * - **VN / InShot**：上一帧 / 下一帧步进，按住连续步进；0.25×～2× 变速播放；
 * - **剪辑软件的标记**：长按胶片或点书签标记多帧，一次存下。
 *
 * 页面结构按 V3「作品 / 内容」配方：内容（舞台）优先 → 关键信息（时间码、帧序号）→ 轻控件 → 一个最强操作
 * （「保存此帧」实色胶囊）。宽屏横放时舞台在左、控制台在右。
 */
class UgoiraFramesFragment : Fragment(R.layout.fragment_ugoira_frames) {

    private val model: UgoiraFramesViewModel by viewModels()
    private lateinit var illust: Illust
    private lateinit var palette: V3Palette

    private lateinit var stage: FrameLayout
    private lateinit var image: ZoomImageView
    private lateinit var loadingBox: LinearLayout
    private lateinit var loadingRing: SeamlessCircularProgressIndicator
    private lateinit var loadingCaption: TextView
    private lateinit var failedBox: LinearLayout
    private lateinit var markBadge: TextView
    private lateinit var flash: View
    private lateinit var glyph: ImageView

    private lateinit var timecode: TextView
    private lateinit var duration: TextView
    private lateinit var frameInfo: TextView
    private lateinit var marksChip: TextView
    private lateinit var timeline: UgoiraTimelineView

    private lateinit var markButton: ImageView
    private lateinit var prevButton: ImageView
    private lateinit var playButton: ImageView
    private lateinit var nextButton: ImageView
    private lateinit var speedButton: TextView
    private lateinit var shareButton: ImageView
    private lateinit var saveButton: TextView

    private var playing = false
    private var positionMs = 0f
    private var lastFrameNs = 0L
    private var saving = false
    private var previewCleared = false
    private var savedFlashUntil = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing || view == null) return
            val total = (model.load.value as? FramesLoad.Ready)?.frames?.totalMs ?: return
            if (lastFrameNs != 0L) {
                val dt = (frameTimeNanos - lastFrameNs) / 1_000_000f * model.speed
                positionMs = (positionMs + dt) % total
            }
            lastFrameNs = frameTimeNanos
            timeline.setPosition(positionMs)
            val i = timeline.indexAt(positionMs)
            if (i != model.index.value) model.show(i)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        palette = V3Palette.from(ctx)
        illust = requireNotNull(BundleCompat.getSerializable(requireArguments(), ARG_ILLUST, Illust::class.java))
        val content = view.findViewById<FrameLayout>(R.id.ugoira_frames_content)
        setupV3Toolbar(view, getString(R.string.ugoira_frames_title), content = content)

        shownMarked = null
        buildStage(ctx)
        val info = buildInfo(ctx)
        timeline = UgoiraTimelineView(ctx).apply {
            thumbAt = model::thumbAt
            listener = timelineListener
        }
        val transport = buildTransport(ctx)
        val actions = buildActions(ctx)
        layOut(ctx, content, info, transport, actions)

        model.start(illust)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.load.collect(::renderLoad) }
                launch { model.frame.collect { it?.let(::renderFrame) } }
                launch { model.index.collect { renderIndex() } }
                launch { model.marks.collect { renderMarks(it) } }
                launch { model.speedIndex.collect { renderSpeed() } }
                launch { model.thumbTick.collect { timeline.refreshThumbs() } }
            }
        }
    }

    override fun onPause() {
        pause(snap = false)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(ticker)
        playing = false
        super.onDestroyView()
    }

    // ── 布局 ────────────────────────────────────────────────────────

    /** 手机竖放：上舞台、下控制台；宽屏横放：舞台在左，控制台 380dp 在右。内容总宽不超过 1200dp。 */
    private fun layOut(ctx: Context, content: FrameLayout, info: View, transport: View, actions: View) {
        val cfg = resources.configuration
        val wide = cfg.screenWidthDp >= 600 && cfg.screenWidthDp > cfg.screenHeightDp
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(info, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = ctx.dp(20); rightMargin = ctx.dp(16); topMargin = ctx.dp(if (wide) 0 else 16)
            })
            addView(timeline, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(12) })
            addView(transport, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = ctx.dp(16); rightMargin = ctx.dp(16); topMargin = ctx.dp(12); bottomMargin = ctx.dp(16)
            })
        }
        val page = LinearLayout(ctx).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (wide) {
            page.addView(stage, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(8), ctx.dp(16))
            })
            page.addView(controls, LinearLayout.LayoutParams(ctx.dp(380), -2))
        } else {
            page.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                setMargins(ctx.dp(16), ctx.dp(12), ctx.dp(16), 0)
            })
            page.addView(controls, LinearLayout.LayoutParams(-1, -2))
        }
        val maxWidth = ctx.dp(if (wide) 1200 else 720)
        content.addView(page, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        content.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left, maxWidth)
            if (page.layoutParams.width != width) {
                page.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
            }
        }
    }

    /** 舞台：26dp 容器里完整显示当前帧，双指 / 双击放大看细节；单击播放 / 暂停。 */
    private fun buildStage(ctx: Context) {
        image = ZoomImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            zoomable.setThreeStepScale(false)
            zoomable.setKeepTransformWhenSameAspectRatioContentSizeChanged(true)
            onViewTapListener = OnViewTapListener { _, _ -> if (view != null) togglePlay() }
            contentDescription = illust.title
        }
        loadingRing = LayoutInflater.from(ctx).inflate(R.layout.item_progress, null, false) as SeamlessCircularProgressIndicator
        loadingCaption = ctx.label(getString(R.string.now_loading), 12f, 500, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        loadingBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = ctx.dp(180)
            setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
            background = shape(ctx.dpF(12f), 0xB3000000.toInt())
            addView(loadingRing, LinearLayout.LayoutParams(-2, -2))
            addView(loadingCaption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
        }
        failedBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isVisible = false
            setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(16))
            background = shape(ctx.dpF(16f), 0xB3000000.toInt())
            addView(ctx.label(getString(R.string.ugoira_frames_load_failed), 14f, 500, Color.WHITE).apply {
                gravity = Gravity.CENTER
            })
            addView(pill(ctx, getString(R.string.retry), R.drawable.ic_baseline_refresh_48, primary = true) {
                model.retry()
            }.apply { minHeight = ctx.dp(48) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(12) })
        }
        markBadge = ctx.label(getString(R.string.ugoira_frames_marked_badge), 12f, 600, palette.floatingPillContent).apply {
            setTextWithIcon(text, R.drawable.ic_reader_bookmark_filled, sizeDp = 14, gapDp = 6)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            background = palette.floatingPillBg(999f)
            isVisible = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        flash = View(ctx).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        glyph = ImageView(ctx).apply {
            background = palette.floatingPillBg(999f)
            imageTintList = ColorStateList.valueOf(palette.floatingPillContent)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage = FrameLayout(ctx).apply {
            background = shape(ctx.dpF(26f), palette.cardFill, palette.cardHairline, ctx.hairlinePx())
            clipToOutline = true
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(flash, FrameLayout.LayoutParams(-1, -1))
            addView(glyph, FrameLayout.LayoutParams(ctx.dp(64), ctx.dp(64), Gravity.CENTER))
            addView(loadingBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(failedBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(markBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            })
        }
        // 帧还没到位时先铺作品预览图，舞台不空着等下载。
        previewCleared = false
        if (model.load.value !is FramesLoad.Ready) {
            GlideUtil.getLargeImage(illust)?.let { Glide.with(this).load(it).dontTransform().into(image) }
        }
    }

    /** 信息行：大号时间码 / 总时长，下面一行帧序号与这一帧停留多久；末端是标记汇总。 */
    private fun buildInfo(ctx: Context): View {
        timecode = ctx.label(formatTime(0), 22f, 600).apply { fontFeatureSettings = "tnum" }
        duration = ctx.label("", 14f, 500, ctx.color(R.color.v3_text_2)).apply { fontFeatureSettings = "tnum" }
        frameInfo = ctx.label("", 12f, 500, ctx.color(R.color.v3_text_2)).apply {
            fontFeatureSettings = "tnum"
            setPadding(0, ctx.dp(4), 0, 0)
        }
        val timeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(timecode)
            addView(duration, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dp(6) })
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(timeRow)
            addView(frameInfo)
        }
        marksChip = ctx.label("", 13f, 600, palette.textAccent).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = ctx.dp(48)
            setPadding(ctx.dp(14), 0, ctx.dp(12), 0)
            background = InsetPill(ctx, palette)
            isVisible = false
            setOnClickListener { clearMarks() }
            pressScale()
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(marksChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dp(12) })
        }
    }

    /** 播放控制：标记 · 上一帧 · 播放（实色圆）· 下一帧 · 速度。 */
    private fun buildTransport(ctx: Context): View {
        val ink = ctx.color(R.color.v3_text_1)
        markButton = roundIcon(ctx, R.drawable.ic_reader_bookmark_border, ink, 48) { toggleMarkCurrent() }
        prevButton = roundIcon(ctx, R.drawable.ic_reader_prev_chapter, ink, 48) { step(-1) }.also {
            it.contentDescription = getString(R.string.ugoira_frames_prev)
            repeatOnHold(it, -1)
        }
        nextButton = roundIcon(ctx, R.drawable.ic_reader_next_chapter, ink, 48) { step(1) }.also {
            it.contentDescription = getString(R.string.ugoira_frames_next)
            repeatOnHold(it, 1)
        }
        playButton = roundIcon(ctx, R.drawable.ic_baseline_play_arrow_24, palette.onPrimary, 64) { togglePlay() }.apply {
            background = ctx.ripple(palette.pillPrimary(999f), shape(999f, Color.WHITE))
            setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
            // renderPlayState 只在第一次播放 / 暂停时才跑，初始名称得在这里给，否则读屏报「未加标签的按钮」。
            contentDescription = getString(R.string.ugoira_frames_play)
        }
        speedButton = ctx.label("1×", 14f, 700).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            minWidth = ctx.dp(48)
            minHeight = ctx.dp(48)
            background = ctx.ripple(null, shape(999f, Color.WHITE))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                model.cycleSpeed()
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            pressScale()
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = ctx.dp(72)
            fun add(v: View, size: Int, gap: Int) = addView(v, LinearLayout.LayoutParams(ctx.dp(size), ctx.dp(size)).apply {
                marginStart = ctx.dp(gap)
            })
            add(markButton, 48, 0)
            add(prevButton, 48, 12)
            add(playButton, 64, 16)
            add(nextButton, 48, 16)
            addView(speedButton, LinearLayout.LayoutParams(-2, ctx.dp(48)).apply { marginStart = ctx.dp(12) })
        }
    }

    /** 主操作行：分享（浅色圆）+ 保存（整宽实色胶囊，按下圆角收到 15dp）。 */
    private fun buildActions(ctx: Context): View {
        shareButton = roundIcon(ctx, R.drawable.ic_share_black_24dp, palette.textAccent, 56) { share() }.apply {
            background = ctx.ripple(palette.pillSecondary(999f, ctx.hairlinePx()), shape(999f, Color.WHITE))
            contentDescription = getString(R.string.share)
        }
        saveButton = pill(ctx, getString(R.string.ugoira_frames_save_one), R.drawable.ic_file_download_black_24dp, primary = true) {
            save()
        }.apply { minHeight = ctx.dp(56) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(shareButton, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
            addView(saveButton, LinearLayout.LayoutParams(0, ctx.dp(56), 1f).apply { marginStart = ctx.dp(12) })
        }
    }

    // ── 渲染 ────────────────────────────────────────────────────────

    private fun renderLoad(state: FramesLoad) {
        val ready = state is FramesLoad.Ready
        loadingBox.isVisible = state is FramesLoad.Loading
        failedBox.isVisible = state is FramesLoad.Failed
        listOf<View>(markButton, prevButton, playButton, nextButton, speedButton, shareButton, saveButton, timeline)
            .forEach {
                it.isEnabled = ready
                it.alpha = if (ready) 1f else .38f
            }
        when (state) {
            is FramesLoad.Loading -> renderProgress(state.progress)
            is FramesLoad.Failed -> pause(snap = false)
            is FramesLoad.Ready -> {
                val frames = state.frames
                if (timeline.frameCount != frames.files.size) {
                    timeline.setFrames(frames.delaysMs)
                    timeline.snapTo(model.index.value, animate = false, fromUser = false)
                    positionMs = timeline.positionMs
                }
                duration.text = "/ " + formatTime(frames.totalMs.toLong())
                renderIndex()
            }
        }
    }

    private fun renderProgress(p: UgoiraProgress?) {
        val pct = p?.percent
        loadingRing.progress = pct ?: 0
        val base = when (p?.phase) {
            UgoiraPhase.DOWNLOAD_ZIP -> getString(R.string.now_downloading)
            UgoiraPhase.INTERPOLATE -> getString(R.string.ugoira_interpolating)
            UgoiraPhase.ENCODE -> getString(R.string.ugoira_encoding)
            else -> getString(R.string.now_loading)
        }
        loadingCaption.text = if (pct != null) "$base  $pct%" else base
    }

    private fun renderFrame(frame: FrameImage) {
        if (!previewCleared) {
            // 预览图请求可能还在路上：先撤掉，免得它晚到把帧盖回去。
            previewCleared = true
            Glide.with(this).clear(image)
        }
        image.setImageBitmap(frame.bitmap)
    }

    private fun renderIndex() {
        val frames = (model.load.value as? FramesLoad.Ready)?.frames ?: return
        val i = model.index.value.coerceIn(0, frames.files.size - 1)
        timecode.text = formatTime(timeline.startOf(i))
        val parts = mutableListOf(
            getString(R.string.ugoira_frames_counter, i + 1, frames.files.size),
            getString(R.string.ugoira_frames_delay, frames.delaysMs[i]),
        )
        if (frames.interpolated) parts += getString(R.string.ugoira_frames_interpolated)
        frameInfo.text = parts.joinToString("  ·  ")
        prevButton.isEnabled = i > 0
        nextButton.isEnabled = i < frames.files.size - 1
        prevButton.alpha = if (prevButton.isEnabled) 1f else .38f
        nextButton.alpha = if (nextButton.isEnabled) 1f else .38f
        renderMarkState()
    }

    private fun renderMarks(marks: Set<Int>) {
        timeline.setMarks(marks)
        marksChip.isVisible = marks.isNotEmpty()
        if (marks.isNotEmpty()) {
            val summary = resources.getQuantityString(R.plurals.ugoira_frames_marked_count, marks.size, marks.size)
            marksChip.setTextWithIconEnd(summary)
            // 读屏要同时听到「标记了几帧」和「点按会清除」，只报动作会丢掉数量。
            marksChip.contentDescription = "$summary, ${getString(R.string.ugoira_frames_clear_marks)}"
        }
        renderMarkState()
        renderSaveLabel()
    }

    /** 上一次画出来的标记态；播放时每一帧都会走到这里，只在状态翻转时才重建图标与背景。 */
    private var shownMarked: Boolean? = null

    private fun renderMarkState() {
        val marked = model.index.value in model.marks.value
        if (marked == shownMarked) return
        shownMarked = marked
        markButton.setImageResource(if (marked) R.drawable.ic_reader_bookmark_filled else R.drawable.ic_reader_bookmark_border)
        markButton.imageTintList = ColorStateList.valueOf(if (marked) palette.textAccent else requireContext().color(R.color.v3_text_1))
        markButton.background = requireContext().ripple(
            if (marked) shape(999f, palette.alpha15) else null,
            shape(999f, Color.WHITE),
        )
        markButton.contentDescription = getString(if (marked) R.string.ugoira_frames_unmark else R.string.ugoira_frames_mark)
        markBadge.isVisible = marked
    }

    private fun renderSpeed() {
        val s = model.speed
        speedButton.text = (if (s < 1f) s.toString().trimEnd('0') else s.toInt().toString()) + "×"
        speedButton.setTextColor(if (s == 1f) requireContext().color(R.color.v3_text_1) else palette.textAccent)
        speedButton.contentDescription = getString(R.string.ugoira_frames_speed, speedButton.text)
    }

    private fun renderPlayState() {
        playButton.setImageResource(if (playing) R.drawable.ic_baseline_pause_24 else R.drawable.ic_baseline_play_arrow_24)
        playButton.contentDescription = getString(if (playing) R.string.ugoira_frames_pause else R.string.ugoira_frames_play)
        renderIndex()
    }

    private fun renderSaveLabel() {
        if (saving || System.currentTimeMillis() < savedFlashUntil) return
        val n = model.marks.value.size
        val text = if (n == 0) getString(R.string.ugoira_frames_save_one)
        else resources.getQuantityString(R.plurals.ugoira_frames_save_many, n, n)
        saveButton.setTextWithIcon(text, R.drawable.ic_file_download_black_24dp)
    }

    // ── 播放与定位 ──────────────────────────────────────────────────

    private val timelineListener = object : UgoiraTimelineView.Listener {
        override fun onScrubStart() = pause(snap = false)

        override fun onPositionChanged(positionMs: Float, frameIndex: Int, fromUser: Boolean) {
            this@UgoiraFramesFragment.positionMs = positionMs
            if (frameIndex != model.index.value) model.show(frameIndex, prefetchForward = frameIndex > model.index.value)
        }

        override fun onToggleMark(frameIndex: Int) {
            model.toggleMark(frameIndex)
        }
    }

    private fun togglePlay() {
        if (model.load.value !is FramesLoad.Ready) return
        if (playing) pause(snap = true) else play()
        pulseGlyph()
    }

    private fun play() {
        if (playing) return
        playing = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(ticker)
        renderPlayState()
    }

    /** 停下；[snap] 时把播放头吸到当前帧中心，停在一帧上而不是两帧之间。 */
    private fun pause(snap: Boolean) {
        if (!playing) return
        playing = false
        Choreographer.getInstance().removeFrameCallback(ticker)
        if (view == null) return
        if (snap) timeline.snapTo(model.index.value, animate = true, fromUser = false)
        renderPlayState()
    }

    /** 走一帧；已到首 / 末帧走不动时返回 false。 */
    private fun step(delta: Int): Boolean {
        if (model.load.value !is FramesLoad.Ready) return false
        pause(snap = false)
        val target = model.index.value + delta
        if (target !in 0 until timeline.frameCount) return false
        timeline.snapTo(target, animate = !holdRepeating, fromUser = false)
        view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    private var holdRepeating = false

    /** 按住上一帧 / 下一帧：500ms 后每 90ms 连续步进，松手停。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatOnHold(button: View, delta: Int) {
        val repeat = object : Runnable {
            override fun run() {
                holdRepeating = true
                if (step(delta)) {
                    handler.postDelayed(this, 90)
                } else {
                    // 走到头：按钮随即被禁用，而禁用的 View 收不到 OnTouchListener 的抬手事件，
                    // 只能在这里自己收尾，否则连发永不停，还会和另一侧的步进抢帧。
                    holdRepeating = false
                    button.isPressed = false
                    button.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }
            }
        }
        button.setOnLongClickListener {
            handler.post(repeat)
            true
        }
        button.setOnTouchListener { v, e ->
            if (motionEnabled()) when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.96f).scaleY(.96f).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(repeat)
                if (holdRepeating) {
                    holdRepeating = false
                    v.isPressed = false
                    return@setOnTouchListener e.actionMasked == MotionEvent.ACTION_UP
                }
            }
            false
        }
    }

    private fun toggleMarkCurrent() {
        if (model.load.value !is FramesLoad.Ready) return
        val i = model.index.value
        val adding = i !in model.marks.value
        model.toggleMark(i)
        markButton.performHapticFeedback(if (adding) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK)
        if (adding && motionEnabled()) {
            markButton.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).withEndAction {
                markButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()
        }
    }

    private fun clearMarks() {
        val previous = model.marks.value
        if (previous.isEmpty()) return
        model.setMarks(emptySet())
        Snackbar.make(
            requireView(),
            resources.getQuantityString(R.plurals.ugoira_frames_marks_cleared, previous.size, previous.size),
            Snackbar.LENGTH_LONG,
        ).setAction(R.string.ugoira_frames_undo) { model.setMarks(previous) }.show()
    }

    // ── 保存与分享 ──────────────────────────────────────────────────

    /** 本次操作的对象：有标记就是全部标记帧，否则是当前帧。 */
    private fun targets(): List<Int> = model.marks.value.sorted().ifEmpty { listOf(model.index.value) }

    private fun save() {
        if (saving || model.load.value !is FramesLoad.Ready) return
        pause(snap = true)
        val targets = targets()
        saving = true
        saveButton.isEnabled = false
        saveButton.setTextWithIcon(getString(R.string.ugoira_frames_saving), null)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { model.save(targets) }
            saving = false
            saveButton.isEnabled = true
            result.onSuccess { count ->
                flashStage()
                saveButton.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.VIRTUAL_KEY,
                )
                Toaster.show(resources.getQuantityString(R.plurals.ugoira_frames_saved_toast, count, count))
                savedFlashUntil = System.currentTimeMillis() + SAVED_LABEL_MS
                saveButton.setTextWithIcon(getString(R.string.ugoira_frames_saved), R.drawable.ic_check_24dp)
                handler.postDelayed({
                    if (view != null) {
                        savedFlashUntil = 0L
                        renderSaveLabel()
                    }
                }, SAVED_LABEL_MS)
            }.onFailure { ex ->
                if (ex is CancellationException) throw ex
                Timber.e(ex, "[frames] save failed illust=%d", illust.id)
                Toaster.show(getString(R.string.save_image_failed, ex.message ?: ex.javaClass.simpleName))
                renderSaveLabel()
            }
        }
    }

    private fun share() {
        if (model.load.value !is FramesLoad.Ready) return
        pause(snap = true)
        val targets = targets()
        viewLifecycleOwner.lifecycleScope.launch {
            val uris = runCatching { model.shareUris(targets) }
                .onFailure { if (it is CancellationException) throw it; Timber.e(it, "[frames] share failed") }
                .getOrNull() ?: return@launch
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }.apply {
                type = "image/jpeg"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }
    }

    // ── 反馈 ────────────────────────────────────────────────────────

    /** 快门式的一次白闪：告诉用户「就是这一帧」被存下了。关闭系统动画时不闪。 */
    private fun flashStage() {
        if (!motionEnabled()) return
        flash.animate().cancel()
        flash.alpha = .35f
        flash.animate().alpha(0f).setDuration(260).start()
    }

    /** 单击舞台时中央浮出一次播放 / 暂停图标（视频编辑器的习惯），随即淡出。 */
    private fun pulseGlyph() {
        glyph.setImageResource(if (playing) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_baseline_pause_24)
        if (!motionEnabled()) return
        glyph.animate().cancel()
        glyph.alpha = 1f
        glyph.scaleX = .9f
        glyph.scaleY = .9f
        glyph.animate().scaleX(1f).scaleY(1f).alpha(0f).setStartDelay(220).setDuration(260).start()
    }

    // ── 小零件 ──────────────────────────────────────────────────────

    private fun roundIcon(ctx: Context, icon: Int, tint: Int, sizeDp: Int, action: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            val pad = ctx.dp((sizeDp - 24) / 2)
            setPadding(pad, pad, pad, pad)
            background = ctx.ripple(null, shape(999f, Color.WHITE))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            pressScale()
        }

    /**
     * V3 胶囊：实色 = 主操作，浅色 = 次操作。按下缩到 0.96，圆角从全圆收到 15dp，松手弹回 ——
     * 形状变化只作回应，不改占位。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun pill(ctx: Context, text: String, icon: Int?, primary: Boolean, action: () -> Unit): TextView {
        val fill = if (primary) palette.pillPrimary(999f) else palette.pillSecondary(999f, ctx.hairlinePx())
        return ctx.label(text, 15f, 600, if (primary) palette.onPrimary else palette.textAccent).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(22), ctx.dp(10), ctx.dp(22), ctx.dp(10))
            background = ctx.ripple(fill, shape(999f, Color.WHITE))
            setTextWithIcon(text, icon)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            var anim: ValueAnimator? = null
            fun morph(to: Float) {
                anim?.cancel()
                val from = if (fill.cornerRadius > height) height / 2f else fill.cornerRadius
                anim = ValueAnimator.ofFloat(from, to).apply {
                    duration = 180
                    addUpdateListener { fill.cornerRadius = it.animatedValue as Float }
                    start()
                }
            }
            setOnTouchListener { v, e ->
                if (motionEnabled() && v.isEnabled) when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(.96f).scaleY(.96f).setDuration(120).start()
                        morph(ctx.dpF(15f))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        morph(v.height / 2f)
                    }
                }
                false
            }
        }
    }

    /** 标记汇总小胶囊：主题浅底 + 描边，末端一个「×」表示点按清除。 */
    private class InsetPill(ctx: Context, palette: V3Palette) :
        android.graphics.drawable.InsetDrawable(palette.pillSecondary(999f, ctx.hairlinePx()), 0, ctx.dp(6), 0, ctx.dp(6)) {
        // 透明上下沿只做 48dp 热区，不能被报成 View 内边距（见 V3 标签流的同一个坑）。
        override fun getPadding(padding: android.graphics.Rect): Boolean {
            padding.set(0, 0, 0, 0)
            return false
        }
    }

    private fun TextView.setTextWithIconEnd(value: String) {
        val close = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_close_black_24dp)?.mutate()?.apply {
            setTint(currentTextColor)
            setBounds(0, 0, context.dp(16), context.dp(16))
        }
        text = value
        setCompoundDrawablesRelative(null, null, close, null)
        compoundDrawablePadding = context.dp(6)
    }

    companion object {
        private const val ARG_ILLUST = "illust"
        private const val SAVED_LABEL_MS = 1600L

        fun newInstance(illust: Illust) = UgoiraFramesFragment().apply {
            arguments = bundleOf(ARG_ILLUST to illust)
        }

        private fun formatTime(ms: Long): String {
            val totalCs = ms / 10
            val cs = totalCs % 100
            val s = (totalCs / 100) % 60
            val m = totalCs / 6000
            return String.format(Locale.US, "%d:%02d.%02d", m, s, cs)
        }
    }
}

/** 打开动图逐帧页。 */
fun openUgoiraFrames(context: Context, illust: Illust) {
    context.startActivity(
        Intent(context, TemplateActivity::class.java)
            .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.UGOIRA_FRAMES.key)
            .putExtra(Params.CONTENT, illust),
    )
}
