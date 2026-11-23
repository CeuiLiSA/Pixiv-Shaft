package ceui.pixiv.ui.debug

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.drawToBitmap
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.card
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.sectionLabel
import ceui.pixiv.witstudio.widget.WitLiquidGlassLayout
import ceui.pixiv.witstudio.widget.WitLiquidGlassMenu
import ceui.pixiv.witstudio.widget.WitLiquidGlassQuality
import ceui.pixiv.witstudio.widget.WitLiquidGlassView
import kotlin.math.abs

/**
 * Debug 专用液态玻璃交互与光学调试页（侧边栏最底部入口，仅 Debug 构建可见）。
 *
 * 提供高对比色带/网格底图、可自由拖拽的玻璃放大胶囊、底部三段操作胶囊与圆形按钮，
 * 以及实时切换 Material / Tone / Live Backdrop / Interactive / CornerRadius / ClearDimming / Tint
 * 与 `drawToBitmap()` 软件快照回归验证。
 */
class LiquidGlassDebugFragment : Fragment() {

    private var selectedMaterial = WitLiquidGlassView.Material.REGULAR
    private var selectedTone = WitLiquidGlassView.Tone.THEME
    private var selectedClearDimming = 0.15f
    private var selectedCornerRadiusDp = Float.POSITIVE_INFINITY
    @ColorInt private var selectedTint = Color.TRANSPARENT
    private var selectedInteractive = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val palette = V3Palette.from(ctx)
        val glassLayout = WitLiquidGlassLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val glassSurfaces = mutableListOf<WitLiquidGlassView>()
        val contentTintTargets = mutableListOf<Pair<WitLiquidGlassView, () -> Unit>>()

        fun syncAllGlass() {
            glassSurfaces.forEach { glass ->
                glass.material = selectedMaterial
                glass.tone = selectedTone
                glass.clearDimming = selectedClearDimming
                glass.cornerRadiusDp = selectedCornerRadiusDp
                glass.tintColor = selectedTint
                glass.isGlassInteractive = selectedInteractive
            }
            contentTintTargets.forEach { (_, applyTint) -> applyTint() }
        }

        // ── Child 0: 可滚动测试底图（含光学色带、网格、控制面板与底部对比色块） ──
        val scrollView = ScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(ctx.color(R.color.v3_bg))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(ctx.dp(16), ctx.dp(88), ctx.dp(16), ctx.dp(132))
        }
        scrollView.addView(content)
        glassLayout.addView(scrollView)

        val statusLabel = ctx.label(
            value = "",
            size = 12f,
            weight = 500,
            color = ctx.color(R.color.v3_text_2),
        )
        fun updateStatusLine() {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mem = am?.memoryClass ?: -1
            val lowRam = am?.isLowRamDevice ?: true
            val backend = when {
                glassLayout.quality == WitLiquidGlassQuality.NONE -> "无（关闭实时采样，轻量静态玻璃）"
                Build.VERSION.SDK_INT < 31 || lowRam || mem <= 128 -> "轻量玻璃（低版本/低内存自动降级）"
                glassLayout.quality == WitLiquidGlassQuality.STANDARD -> "标准 · RenderEffect 实时模糊 (API ${Build.VERSION.SDK_INT})"
                Build.VERSION.SDK_INT >= 33 -> "极致 · AGSL 实时折射与色散 (API ${Build.VERSION.SDK_INT})"
                else -> "标准（受限于系统版本）· RenderEffect 实时模糊 (API ${Build.VERSION.SDK_INT})"
            }
            statusLabel.text = "API ${Build.VERSION.SDK_INT} · heap=${mem}MB · lowRam=$lowRam\n等级：${glassLayout.quality.name} · 管线：$backend"
        }

        // 顶部标题与运行态诊断
        content.addView(
            ctx.label("Liquid Glass Debug", size = 24f, weight = 800, color = ctx.color(R.color.v3_text_1)),
        )
        content.addView(
            statusLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(6) },
        )

        // 光学测试舞台 1：彩虹渐变 + 几何网格 + 黑白对比条（最直观检验折射、色散、霜化）
        content.addView(
            OpticalStageView(ctx, variant = 0),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(240)).apply {
                topMargin = ctx.dp(16)
            },
        )

        // 控制面板卡片
        val controlsCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.card(22)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }
        content.addView(
            controlsCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(16) },
        )

        var materialRowUpdater: ((Int) -> Unit)? = null
        materialRowUpdater = controlsCard.addOptionRow(
            title = "Material",
            options = listOf("REGULAR", "CLEAR"),
            initialIndex = 0,
            palette = palette,
        ) { index ->
            selectedMaterial = if (index == 0) WitLiquidGlassView.Material.REGULAR else WitLiquidGlassView.Material.CLEAR
            syncAllGlass()
        }

        var toneRowUpdater: ((Int) -> Unit)? = null
        toneRowUpdater = controlsCard.addOptionRow(
            title = "Tone",
            options = listOf("THEME", "LIGHT", "DARK"),
            initialIndex = 0,
            palette = palette,
        ) { index ->
            selectedTone = when (index) {
                1 -> WitLiquidGlassView.Tone.LIGHT
                2 -> WitLiquidGlassView.Tone.DARK
                else -> WitLiquidGlassView.Tone.THEME
            }
            syncAllGlass()
        }

        var liveRowUpdater: ((Int) -> Unit)? = null
        liveRowUpdater = controlsCard.addOptionRow(
            title = "效果等级 (Quality)",
            options = listOf("无", "标准", "极致"),
            initialIndex = when (glassLayout.quality) {
                WitLiquidGlassQuality.NONE -> 0
                WitLiquidGlassQuality.STANDARD -> 1
                WitLiquidGlassQuality.ULTRA -> 2
            },
            palette = palette,
        ) { index ->
            glassLayout.quality = when (index) {
                1 -> WitLiquidGlassQuality.STANDARD
                2 -> WitLiquidGlassQuality.ULTRA
                else -> WitLiquidGlassQuality.NONE
            }
            updateStatusLine()
        }

        controlsCard.addOptionRow(
            title = "Interactive 弹性按压",
            options = listOf("开启", "关闭"),
            initialIndex = 0,
            palette = palette,
        ) { index ->
            selectedInteractive = index == 0
            syncAllGlass()
        }

        controlsCard.addOptionRow(
            title = "Corner Radius",
            options = listOf("胶囊", "24dp", "14dp", "6dp"),
            initialIndex = 0,
            palette = palette,
        ) { index ->
            selectedCornerRadiusDp = when (index) {
                1 -> 24f
                2 -> 14f
                3 -> 6f
                else -> Float.POSITIVE_INFINITY
            }
            syncAllGlass()
        }

        controlsCard.addOptionRow(
            title = "Tint Wash",
            options = listOf("无", "主题", "青蓝", "粉红"),
            initialIndex = 0,
            palette = palette,
        ) { index ->
            selectedTint = when (index) {
                1 -> V3Palette.withAlpha(palette.primary, 0.22f)
                2 -> 0x402B6FCC
                3 -> 0x40E0246A
                else -> Color.TRANSPARENT
            }
            syncAllGlass()
        }

        // Clear Dimming 滑条（仅 CLEAR 生效，实时拖看纯白底可读性）
        val dimmingHeader = ctx.sectionLabel("Clear Dimming · 15%")
        controlsCard.addView(
            dimmingHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(14) },
        )
        val dimmingSeek = SeekBar(ctx).apply {
            max = 60
            progress = 15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedClearDimming = progress / 100f
                    dimmingHeader.text = "Clear Dimming · $progress%"
                    syncAllGlass()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controlsCard.addView(
            dimmingSeek,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(4) },
        )

        // drawToBitmap 软件快照测试按钮（验证软件画布不破坏在线硬件底图）
        val snapshotBtn = ctx.label(
            value = "drawToBitmap() 软件快照回归测试",
            size = 13f,
            weight = 600,
            color = palette.textAccent,
        ).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(44)
            background = palette.pillSecondary(ctx.dpF(999f), ctx.dp(1).coerceAtLeast(1))
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val bmp = glassLayout.drawToBitmap()
                val info = "软件快照成功：${bmp.width}×${bmp.height}，请继续滚动确认在线玻璃仍正常折射"
                bmp.recycle()
                Common.showToast(info)
            }
        }
        controlsCard.addView(
            snapshotBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(14) },
        )

        // 光学测试舞台 2 & 3：明暗阶梯 + 高对比文字卡片（供滚过底部悬浮胶囊与右下圆钮观察）
        content.addView(
            OpticalStageView(ctx, variant = 1),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(260)).apply {
                topMargin = ctx.dp(16)
            },
        )
        content.addView(
            OpticalStageView(ctx, variant = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(260)).apply {
                topMargin = ctx.dp(16)
            },
        )

        // ── 悬浮液态玻璃层（WitLiquidGlassLayout 直接子 View） ──

        // 1) 左上返回圆钮
        val backIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_back_white_shadow)
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        }
        val backGlass = WitLiquidGlassView(ctx).apply {
            isGlassInteractive = true
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.reader_desc_back)
            addView(backIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { requireActivity().finish() }
        }
        glassSurfaces += backGlass
        contentTintTargets += backGlass to {
            backIcon.imageTintList = ColorStateList.valueOf(backGlass.contentColor)
        }
        glassLayout.addView(
            backGlass,
            FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.START).apply {
                marginStart = ctx.dp(16)
                topMargin = ctx.dp(16)
            },
        )

        // 2) 右上角 iOS 风格液态玻璃展开菜单按钮
        val menuIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_more_vert_black_24dp)
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        }
        val toggleGlass = WitLiquidGlassView(ctx).apply {
            isGlassInteractive = true
            isClickable = true
            isFocusable = true
            contentDescription = "打开液态玻璃展开菜单"
            addView(menuIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        glassSurfaces += toggleGlass
        contentTintTargets += toggleGlass to {
            menuIcon.imageTintList = ColorStateList.valueOf(toggleGlass.contentColor)
        }
        glassLayout.addView(
            toggleGlass,
            FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.END).apply {
                marginEnd = ctx.dp(16)
                topMargin = ctx.dp(16)
            },
        )

        // 3) 可全屏自由拖拽的玻璃透镜胶囊（检验 translation 实时重采样与边缘折射）
        val loupeText = ctx.label("拖我四处移动", size = 13f, weight = 700, color = Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        }
        val loupeGlass = WitLiquidGlassView(ctx).apply {
            isGlassInteractive = true
            isClickable = true
            addView(
                loupeText,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            installFreeDrag()
        }
        glassSurfaces += loupeGlass
        contentTintTargets += loupeGlass to {
            loupeText.setTextColor(loupeGlass.contentColor)
        }
        glassLayout.addView(
            loupeGlass,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = ctx.dp(190)
            },
        )

        toggleGlass.setOnClickListener { anchor ->
            WitLiquidGlassMenu.show(glassLayout, anchor) {
                item(
                    label = "效果等级：无",
                    icon = R.drawable.ic_visibility_off_black_24dp,
                    checked = glassLayout.quality == WitLiquidGlassQuality.NONE,
                ) {
                    glassLayout.quality = WitLiquidGlassQuality.NONE
                    liveRowUpdater?.invoke(0)
                    updateStatusLine()
                }
                item(
                    label = "效果等级：标准",
                    icon = R.drawable.baseline_auto_awesome_24,
                    checked = glassLayout.quality == WitLiquidGlassQuality.STANDARD,
                ) {
                    glassLayout.quality = WitLiquidGlassQuality.STANDARD
                    liveRowUpdater?.invoke(1)
                    updateStatusLine()
                }
                item(
                    label = "效果等级：极致",
                    icon = R.drawable.baseline_auto_awesome_24,
                    checked = glassLayout.quality == WitLiquidGlassQuality.ULTRA,
                ) {
                    glassLayout.quality = WitLiquidGlassQuality.ULTRA
                    liveRowUpdater?.invoke(2)
                    updateStatusLine()
                }
                divider()
                item(
                    label = "REGULAR 常规材质",
                    icon = R.drawable.ic_photo_filter_24,
                    checked = selectedMaterial == WitLiquidGlassView.Material.REGULAR,
                ) {
                    selectedMaterial = WitLiquidGlassView.Material.REGULAR
                    materialRowUpdater?.invoke(0)
                    syncAllGlass()
                }
                item(
                    label = "CLEAR 高透材质",
                    icon = R.drawable.ic_remove_red_eye_black_24dp,
                    checked = selectedMaterial == WitLiquidGlassView.Material.CLEAR,
                ) {
                    selectedMaterial = WitLiquidGlassView.Material.CLEAR
                    materialRowUpdater?.invoke(1)
                    syncAllGlass()
                }
                item(
                    label = "切换极性 (THEME/LIGHT/DARK)",
                    icon = R.drawable.ic_baseline_filter_24,
                ) {
                    val nextIdx = when (selectedTone) {
                        WitLiquidGlassView.Tone.THEME -> 1
                        WitLiquidGlassView.Tone.LIGHT -> 2
                        WitLiquidGlassView.Tone.DARK -> 0
                    }
                    selectedTone = when (nextIdx) {
                        1 -> WitLiquidGlassView.Tone.LIGHT
                        2 -> WitLiquidGlassView.Tone.DARK
                        else -> WitLiquidGlassView.Tone.THEME
                    }
                    toneRowUpdater?.invoke(nextIdx)
                    syncAllGlass()
                }
                divider()
                item("复位拖拽胶囊", R.drawable.ic_keyboard_arrow_up_black_24dp) {
                    loupeGlass.animate().translationX(0f).translationY(0f).setDuration(240L).start()
                }
                item("软件快照回归测试", R.drawable.ic_setcat_photo) {
                    val bmp = glassLayout.drawToBitmap()
                    val info = "软件快照成功：${bmp.width}×${bmp.height}"
                    bmp.recycle()
                    Common.showToast(info)
                }
                divider()
                item("危险操作示例", R.drawable.ic_visibility_off_black_24dp, destructive = true) {
                    Common.showToast("点击了危险操作示例")
                }
            }
        }

        // 4) 底部中央三段操作胶囊（对齐插画详情 fab_bar，可按住拖远测试弹性形变与回弹）
        val dockIcons = listOf(
            R.drawable.ic_file_download_black_24dp to "下载",
            R.drawable.ic_favorite_black_24dp to "收藏",
            R.drawable.ic_baseline_comment_24 to "评论",
        )
        val dockImageViews = mutableListOf<ImageView>()
        val dockDividers = mutableListOf<View>()
        val dockRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(6), 0, ctx.dp(6), 0)
        }
        val dockGlass = WitLiquidGlassView(ctx).apply {
            isGlassInteractive = true
            addView(dockRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        dockIcons.forEachIndexed { i, (iconRes, title) ->
            if (i > 0) {
                val divider = View(ctx)
                dockDividers += divider
                dockRow.addView(
                    divider,
                    LinearLayout.LayoutParams(ctx.dp(1), ctx.dp(24)).apply {
                        marginStart = ctx.dp(2)
                        marginEnd = ctx.dp(2)
                    },
                )
            }
            val btn = ImageView(ctx).apply {
                setImageResource(iconRes)
                setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = title
                setOnClickListener { Common.showToast("点击：$title（长按并拖远可试按压形变与回弹）") }
            }
            dockImageViews += btn
            dockRow.addView(btn, LinearLayout.LayoutParams(ctx.dp(60), ctx.dp(40)))
        }
        glassSurfaces += dockGlass
        contentTintTargets += dockGlass to {
            val tint = dockGlass.contentColor
            val divColor = V3Palette.withAlpha(tint, 0.20f)
            dockImageViews.forEach { it.imageTintList = ColorStateList.valueOf(tint) }
            dockDividers.forEach { it.setBackgroundColor(divColor) }
        }
        glassLayout.addView(
            dockGlass,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = ctx.dp(24)
            },
        )

        // 5) 右下角圆形回顶玻璃按钮
        val topIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_keyboard_arrow_up_black_24dp)
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        }
        val topGlass = WitLiquidGlassView(ctx).apply {
            isGlassInteractive = true
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.feed_back_to_top_fab)
            addView(topIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { scrollView.smoothScrollTo(0, 0) }
        }
        glassSurfaces += topGlass
        contentTintTargets += topGlass to {
            topIcon.imageTintList = ColorStateList.valueOf(topGlass.contentColor)
        }
        glassLayout.addView(
            topGlass,
            FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = ctx.dp(16)
                bottomMargin = ctx.dp(24)
            },
        )

        // 适配状态栏、刘海与导航栏 inset
        ViewCompat.setOnApplyWindowInsetsListener(glassLayout) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            content.setPadding(
                bars.left + ctx.dp(16),
                bars.top + ctx.dp(76),
                bars.right + ctx.dp(16),
                bars.bottom + ctx.dp(116),
            )
            (backGlass.layoutParams as FrameLayout.LayoutParams).let { lp ->
                lp.topMargin = bars.top + ctx.dp(12)
                lp.marginStart = bars.left + ctx.dp(16)
                backGlass.layoutParams = lp
            }
            (toggleGlass.layoutParams as FrameLayout.LayoutParams).let { lp ->
                lp.topMargin = bars.top + ctx.dp(12)
                lp.marginEnd = bars.right + ctx.dp(16)
                toggleGlass.layoutParams = lp
            }
            (loupeGlass.layoutParams as FrameLayout.LayoutParams).let { lp ->
                lp.topMargin = bars.top + ctx.dp(180)
                loupeGlass.layoutParams = lp
            }
            (dockGlass.layoutParams as FrameLayout.LayoutParams).let { lp ->
                lp.bottomMargin = bars.bottom + ctx.dp(24)
                dockGlass.layoutParams = lp
            }
            (topGlass.layoutParams as FrameLayout.LayoutParams).let { lp ->
                lp.bottomMargin = bars.bottom + ctx.dp(24)
                lp.marginEnd = bars.right + ctx.dp(16)
                topGlass.layoutParams = lp
            }
            windowInsets
        }

        syncAllGlass()
        updateStatusLine()
        return glassLayout
    }

    /** 允许胶囊被单指自由拖过整块屏幕，并限制在宿主可视区域内；轻点复位到初始位置。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.installFreeDrag() {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
        var moved = false
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    if (!moved && (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop)) {
                        moved = true
                    }
                    val host = v.parent as? View
                    var nextTx = v.translationX + dx
                    var nextTy = v.translationY + dy
                    if (host != null && host.width > v.width && host.height > v.height) {
                        val minTx = -v.left.toFloat()
                        val maxTx = (host.width - v.right).toFloat()
                        val minTy = -v.top.toFloat()
                        val maxTy = (host.height - v.bottom).toFloat()
                        nextTx = nextTx.coerceIn(minTx, maxTx)
                        nextTy = nextTy.coerceIn(minTy, maxTy)
                    }
                    v.translationX = nextTx
                    v.translationY = nextTy
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        v.animate().translationX(0f).translationY(0f).setDuration(240L).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 在控制面板卡片里生成一行互斥胶囊选择器，返回可用于外部同步选中态的回调。 */
    private fun LinearLayout.addOptionRow(
        title: String,
        options: List<String>,
        initialIndex: Int,
        palette: V3Palette,
        onSelected: (Int) -> Unit,
    ): (Int) -> Unit {
        val ctx = context
        addView(
            ctx.sectionLabel(title),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (childCount > 0) topMargin = ctx.dp(14)
            },
        )
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val chips = mutableListOf<TextView>()
        var current = initialIndex

        fun renderSelection(selected: Int) {
            current = selected
            chips.forEachIndexed { idx, chip ->
                val active = idx == selected
                chip.setTextColor(if (active) palette.onPrimary else palette.textAccent)
                chip.background = if (active) {
                    palette.pillPrimary(ctx.dpF(999f))
                } else {
                    palette.pillSecondary(ctx.dpF(999f), ctx.dp(1).coerceAtLeast(1))
                }
            }
        }

        options.forEachIndexed { idx, text ->
            val chip = ctx.label(text, size = 12f, weight = 600, color = palette.textAccent).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dp(38)
                setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (current != idx) {
                        renderSelection(idx)
                        onSelected(idx)
                    }
                }
            }
            chips += chip
            row.addView(
                chip,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = ctx.dp(6)
                },
            )
        }
        renderSelection(initialIndex)
        addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(8) },
        )
        return ::renderSelection
    }

    /** 高对比光学测试底图：渐变色带 + 精细网格 + 黑白对比色块 + 大字号文字。 */
    private class OpticalStageView(
        context: android.content.Context,
        private val variant: Int,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private var gradient: Shader? = null

        init {
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(20f)
                setColor(Color.BLACK)
            }
            clipToOutline = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            val colors = when (variant) {
                0 -> intArrayOf(0xFF0B1026.toInt(), 0xFF686BDD.toInt(), 0xFFFF3B7F.toInt(), 0xFFFFB830.toInt(), 0xFF19D3B0.toInt())
                1 -> intArrayOf(0xFF000000.toInt(), 0xFF1A1A2E.toInt(), 0xFF8888A0.toInt(), 0xFFFFFFFF.toInt())
                else -> intArrayOf(0xFF1B0A2A.toInt(), 0xFFE63946.toInt(), 0xFF457B9D.toInt(), 0xFFA8DADC.toInt(), 0xFFF1FAEE.toInt())
            }
            gradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val d = resources.displayMetrics.density

            // 背景渐变
            paint.style = Paint.Style.FILL
            paint.shader = gradient
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // 高对比网格线（最容易看清边缘折射弯曲与 RGB 色散）
            val step = 22f * d
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * d
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, 120)
            var x = step
            while (x < w) {
                canvas.drawLine(x, 0f, x, h, paint)
                x += step
            }
            var y = step
            while (y < h) {
                canvas.drawLine(0f, y, w, y, paint)
                y += step
            }

            // 饱和色圆块 + 黑白条带
            paint.style = Paint.Style.FILL
            when (variant) {
                0 -> {
                    paint.color = 0xFFFFD166.toInt()
                    canvas.drawCircle(w * 0.22f, h * 0.34f, 34f * d, paint)
                    paint.color = 0xFF06D6A0.toInt()
                    canvas.drawCircle(w * 0.78f, h * 0.66f, 40f * d, paint)
                    paint.color = 0xFFEF476F.toInt()
                    canvas.drawCircle(w * 0.50f, h * 0.72f, 26f * d, paint)
                }
                1 -> {
                    val stripes = 8
                    val sw = w / stripes
                    for (i in 0 until stripes) {
                        paint.color = if (i % 2 == 0) Color.WHITE else Color.BLACK
                        canvas.drawRect(i * sw, h * 0.62f, (i + 1) * sw, h, paint)
                    }
                }
                else -> {
                    paint.color = 0xFF00F5D4.toInt()
                    canvas.drawRect(w * 0.08f, h * 0.16f, w * 0.42f, h * 0.42f, paint)
                    paint.color = 0xFFFF006E.toInt()
                    canvas.drawRect(w * 0.58f, h * 0.56f, w * 0.92f, h * 0.84f, paint)
                }
            }

            // 锐利文字标尺
            textPaint.textSize = 20f * d
            textPaint.color = Color.WHITE
            val caption = when (variant) {
                0 -> "REFRACTION · CHROMATIC · SPECULAR"
                1 -> "DARK ↔ LIGHT CONTRAST RAMP"
                else -> "SCROLL BEHIND BOTTOM GLASS DOCK"
            }
            canvas.drawText(caption, w * 0.5f, h * 0.52f, textPaint)
        }
    }
}
