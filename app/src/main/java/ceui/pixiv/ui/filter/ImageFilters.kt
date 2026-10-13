package ceui.pixiv.ui.filter

import androidx.annotation.StringRes
import ceui.lisa.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 滤镜的颜色模型。
 *
 * 当下修图 App 的热门滤镜（VSCO 的 A6 / M5、醒图的奶油 / 氛围感、Dazz 的 CCD、电影青橙……）
 * 靠的都是**分通道曲线 + 分离色调**，一只颜色矩阵表达不了。所以这里的每个滤镜都是一串 [ColorOp]，
 * 再连同强度和手动调节一起采样成一只 33³ 的 **3D LUT**（VSCO / Lightroom 的同一种做法）：
 * 无论叠了多少步，像素侧都只是一次查表 + 四面体插值，预览与导出走同一个 native 内核。
 *
 * 颜色一律在 0–1 浮点域里算，中途允许越界，采样进 LUT 时才夹回去。
 */
fun interface ColorOp {
    /** 原地改写 [c]（r, g, b）。 */
    fun apply(c: FloatArray)
}

/** 由控制点（0–255）定义的单调三次曲线，预先采样成 256 级表。 */
class Curve private constructor(private val table: FloatArray) {

    fun eval(v: Float): Float {
        val x = v.coerceIn(0f, 1f) * 255f
        val i = x.toInt().coerceAtMost(254)
        val f = x - i
        return table[i] + (table[i + 1] - table[i]) * f
    }

    companion object {
        /** [points] 是成对的 x, y（0–255），x 递增。Fritsch–Carlson 保单调，不会在控制点之间过冲。 */
        fun of(vararg points: Int): Curve {
            require(points.size >= 4 && points.size % 2 == 0) { "curve needs ≥ 2 points" }
            val n = points.size / 2
            val xs = FloatArray(n) { points[it * 2] / 255f }
            val ys = FloatArray(n) { points[it * 2 + 1] / 255f }
            val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
            val m = FloatArray(n) { i ->
                when (i) {
                    0 -> d[0]
                    n - 1 -> d[n - 2]
                    else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
                }
            }
            for (i in 0 until n - 1) {
                if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / kotlin.math.sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
            val table = FloatArray(256) { k ->
                val x = k / 255f
                when {
                    x <= xs[0] -> ys[0]
                    x >= xs[n - 1] -> ys[n - 1]
                    else -> {
                        var i = 0
                        while (x > xs[i + 1]) i++
                        val h = xs[i + 1] - xs[i]
                        val t = (x - xs[i]) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
                            (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                    }
                }
            }
            return Curve(table)
        }
    }
}

/** 调色步骤的工厂。参数含义尽量对齐修图 App 的滑条：0 = 不变，±1 = 满档。 */
object Ops {

    fun luma(c: FloatArray): Float = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    /** 曝光（档）：线性乘 2^ev。 */
    fun exposure(ev: Float) = ColorOp { c ->
        val k = 2f.pow(ev)
        c[0] *= k; c[1] *= k; c[2] *= k
    }

    /**
     * 亮度：伽马式提亮 / 压暗中间调，黑白两端不动 —— 修图 App 的「亮度」都是这个手感，
     * 拉满也不会像曝光那样把高光整片冲白。[b] ∈ [-1, 1]。
     */
    fun brightness(b: Float) = ColorOp { c ->
        val g = 2f.pow(-0.75f * b)
        for (i in 0..2) if (c[i] > 0f) c[i] = c[i].pow(g)
    }

    /** 以中灰为支点的线性对比。 */
    fun contrast(k: Float) = ColorOp { c ->
        for (i in 0..2) c[i] = (c[i] - 0.5f) * k + 0.5f
    }

    /** 饱和度：绕亮度缩放色度，[s] = 0 为灰度。 */
    fun saturation(s: Float) = ColorOp { c ->
        val l = luma(c)
        for (i in 0..2) c[i] = l + (c[i] - l) * s
    }

    /** 自然饱和度：越不饱和的颜色加得越多，肤色不容易过火。 */
    fun vibrance(v: Float) = ColorOp { c ->
        val mx = max(c[0], max(c[1], c[2]))
        val mn = min(c[0], min(c[1], c[2]))
        val sat = (mx - mn).coerceIn(0f, 1f)
        val s = 1f + v * (1f - sat)
        val l = luma(c)
        for (i in 0..2) c[i] = l + (c[i] - l) * s
    }

    /** 色温：正值偏暖（加红减蓝）。 */
    fun warmth(w: Float) = ColorOp { c ->
        c[0] *= 1f + 0.10f * w; c[1] *= 1f + 0.02f * w; c[2] *= 1f - 0.14f * w
    }

    /** 色调：正值偏洋红，负值偏绿。 */
    fun tint(t: Float) = ColorOp { c ->
        c[0] *= 1f + 0.04f * t; c[1] *= 1f - 0.08f * t; c[2] *= 1f + 0.04f * t
    }

    /** 褪色：托起黑位、压低白位，哑光相纸感。 */
    fun fade(a: Float) = ColorOp { c ->
        val s = 1f - 0.26f * a
        val o = 0.15f * a
        for (i in 0..2) c[i] = c[i] * s + o
    }

    /** 高光 / 阴影：按亮度加权的提亮或压暗，只动对应的那一段。 */
    fun highlights(h: Float) = ColorOp { c ->
        val l = luma(c).coerceIn(0f, 1f)
        val d = 0.28f * h * l * l
        for (i in 0..2) c[i] += d
    }

    fun shadows(s: Float) = ColorOp { c ->
        val l = 1f - luma(c).coerceIn(0f, 1f)
        val d = 0.28f * s * l * l
        for (i in 0..2) c[i] += d
    }

    /** 曲线：先过总曲线，再过各通道曲线（与 Lightroom 的 RGB / 红 / 绿 / 蓝 曲线同序）。 */
    fun curves(master: Curve? = null, r: Curve? = null, g: Curve? = null, b: Curve? = null) = ColorOp { c ->
        if (master != null) for (i in 0..2) c[i] = master.eval(c[i])
        if (r != null) c[0] = r.eval(c[0])
        if (g != null) c[1] = g.eval(c[1])
        if (b != null) c[2] = b.eval(c[2])
    }

    /**
     * 分离色调：暗部推向 [shadow]、亮部推向 [highlight]（0xRRGGBB）。只加色度，不加亮度：
     * 色相减去它自己的亮度后再叠，所以「青橙」不会让整张图发灰或变亮。
     */
    fun splitTone(shadow: Int, shadowAmount: Float, highlight: Int, highlightAmount: Float) = ColorOp { c ->
        val sh = chroma(shadow)
        val hi = chroma(highlight)
        val l = luma(c).coerceIn(0f, 1f)
        val ws = (1f - l) * (1f - l) * shadowAmount
        val wh = l * l * highlightAmount
        for (i in 0..2) c[i] += sh[i] * ws + hi[i] * wh
    }

    /** 通道混合：3 × 3 矩阵，行是输出。 */
    fun mix(vararg m: Float): ColorOp {
        require(m.size == 9) { "mix needs a 3×3 matrix" }
        return ColorOp { c -> mixInto(m, c) }
    }

    private fun mixInto(m: FloatArray, c: FloatArray) {
        val r = c[0]; val g = c[1]; val b = c[2]
        c[0] = m[0] * r + m[1] * g + m[2] * b
        c[1] = m[3] * r + m[4] * g + m[5] * b
        c[2] = m[6] * r + m[7] * g + m[8] * b
    }

    /** 灰度（Rec.601 加权，黑白片观感更厚）。 */
    val grayscale = ColorOp { c ->
        val l = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
        c[0] = l; c[1] = l; c[2] = l
    }

    /** 经典 Sepia 系数。 */
    val sepia = mix(0.393f, 0.769f, 0.189f, 0.349f, 0.686f, 0.168f, 0.272f, 0.534f, 0.131f)

    private fun chroma(rgb: Int): FloatArray {
        val c = floatArrayOf((rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f)
        val l = luma(c)
        return floatArrayOf(c[0] - l, c[1] - l, c[2] - l)
    }
}

/** 滤镜条里的分组，按「从哪类 App 学来的」分，方便找。 */
enum class FilterGroup(@StringRes val label: Int) {
    PORTRAIT(R.string.image_filter_group_portrait),
    FILM(R.string.image_filter_group_film),
    CINEMA(R.string.image_filter_group_cinema),
    MONO(R.string.image_filter_group_mono),
}

/**
 * 预设滤镜。注释里写了它对标的热门滤镜；界面上只用描述性的名字，不出现品牌与胶卷型号。
 * [vignette] / [grain] 是滤镜自带的暗角与颗粒，随强度一起缩放。
 * [key] 写进 SavedState 与导出文件名，改名会让旋转 / 进程回收后的选择丢失。
 */
enum class FilterPreset(
    val key: String,
    @StringRes val label: Int,
    val group: FilterGroup?,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val ops: () -> List<ColorOp>,
) {
    ORIGINAL("original", R.string.image_filter_preset_original, null, ops = { emptyList() }),

    // ── 人像清新：醒图 / 美图秀秀 / 黄油相机 ─────────────────────────

    /** 奶油：托起的黑位 + 柔和高光 + 偏暖偏粉、低饱和（醒图「奶油」系）。 */
    CREAM("cream", R.string.image_filter_preset_cream, FilterGroup.PORTRAIT, ops = {
        listOf(
            Ops.curves(master = Curve.of(0, 26, 64, 80, 128, 142, 192, 203, 255, 246)),
            Ops.warmth(0.22f), Ops.tint(0.12f), Ops.saturation(0.84f),
            Ops.splitTone(0xC8A08C, 0.10f, 0xFFD2B4, 0.12f),
        )
    }),

    /** 清透：提曝光、压高光留层次、补阴影，微冷，通透干净。 */
    CLEAR("clear", R.string.image_filter_preset_clear, FilterGroup.PORTRAIT, ops = {
        listOf(
            Ops.exposure(0.22f), Ops.highlights(-0.35f), Ops.shadows(0.3f),
            Ops.warmth(-0.15f), Ops.vibrance(0.25f),
            Ops.curves(master = Curve.of(0, 4, 70, 66, 180, 190, 255, 255)),
        )
    }),

    /** 冷白：提亮、去黄，微洋红防发绿，冷白皮。 */
    COOL_WHITE("cool_white", R.string.image_filter_preset_cool_white, FilterGroup.PORTRAIT, ops = {
        listOf(
            Ops.exposure(0.14f), Ops.highlights(-0.2f), Ops.warmth(-0.4f), Ops.tint(0.06f), Ops.saturation(0.86f),
            Ops.curves(master = Curve.of(0, 6, 128, 136, 255, 255)),
        )
    }),

    /** 日杂：褪色 + 青绿暗部 + 低饱和，日系杂志感。 */
    MAGAZINE("magazine", R.string.image_filter_preset_magazine, FilterGroup.PORTRAIT, ops = {
        listOf(
            Ops.fade(0.4f), Ops.exposure(0.12f), Ops.saturation(0.78f),
            Ops.mix(0.95f, 0.05f, 0f, 0f, 1.02f, 0f, 0f, 0.04f, 0.98f),
            Ops.splitTone(0x5FA8A0, 0.16f, 0xFFF0D8, 0.06f),
        )
    }),

    /** 蜜桃：暖粉高光、自然饱和度拉起，元气少女感。 */
    PEACH("peach", R.string.image_filter_preset_peach, FilterGroup.PORTRAIT, ops = {
        listOf(
            Ops.exposure(0.1f), Ops.vibrance(0.3f), Ops.tint(0.18f), Ops.warmth(0.12f),
            Ops.splitTone(0xB07890, 0.08f, 0xFFB8B0, 0.18f),
            Ops.curves(master = Curve.of(0, 10, 128, 134, 255, 252)),
        )
    }),

    // ── 胶片：VSCO / Dazz / 经典胶卷 ─────────────────────────────

    /** 褪色胶片（VSCO A6）：大幅褪色、低对比、微暖、各通道黑位抬起。 */
    ANALOG_FADE("analog_fade", R.string.image_filter_preset_analog_fade, FilterGroup.FILM, grain = 0.15f, ops = {
        listOf(
            Ops.curves(
                master = Curve.of(0, 34, 60, 72, 128, 132, 200, 196, 255, 236),
                b = Curve.of(0, 18, 255, 240),
            ),
            Ops.saturation(0.78f), Ops.warmth(0.1f),
        )
    }),

    /** 鲜亮胶片（VSCO C1）：明亮、通透、饱和，带一点暖。 */
    VIVID_FILM("vivid_film", R.string.image_filter_preset_vivid_film, FilterGroup.FILM, ops = {
        listOf(
            Ops.exposure(0.05f), Ops.vibrance(0.35f), Ops.saturation(1.1f), Ops.warmth(0.08f),
            Ops.curves(master = Curve.of(0, 8, 64, 58, 192, 200, 255, 252)),
        )
    }),

    /** 暖黄哑光（VSCO M5）：偏黄、褪色、低饱和的复古日常。 */
    MUTED_WARM("muted_warm", R.string.image_filter_preset_muted_warm, FilterGroup.FILM, grain = 0.12f, ops = {
        listOf(
            Ops.fade(0.32f), Ops.saturation(0.72f),
            Ops.curves(b = Curve.of(0, 20, 128, 112, 255, 220), r = Curve.of(0, 6, 128, 134, 255, 252)),
        )
    }),

    /** 冷调褪色（VSCO HB2）：冷、蓝色暗部、褪色。 */
    COOL_FADE("cool_fade", R.string.image_filter_preset_cool_fade, FilterGroup.FILM, ops = {
        listOf(
            Ops.fade(0.28f), Ops.warmth(-0.35f), Ops.saturation(0.8f),
            Ops.splitTone(0x4870A8, 0.18f, 0xE8F0FF, 0.05f),
        )
    }),

    /** 暖调负片（人像负片）：暖肤色、柔和对比、粉彩色。 */
    WARM_NEGATIVE("warm_negative", R.string.image_filter_preset_warm_negative, FilterGroup.FILM, grain = 0.18f, ops = {
        listOf(
            Ops.curves(
                master = Curve.of(0, 16, 64, 66, 128, 134, 192, 198, 255, 245),
                r = Curve.of(0, 4, 128, 136, 255, 255),
                b = Curve.of(0, 12, 128, 122, 255, 238),
            ),
            Ops.saturation(0.86f), Ops.splitTone(0x6C8C78, 0.08f, 0xFFD8B8, 0.1f),
        )
    }),

    /** 金色胶片（消费级暖黄胶卷）：浓郁的金黄、偏高饱和，暗角 + 颗粒。 */
    GOLDEN("golden", R.string.image_filter_preset_golden, FilterGroup.FILM, vignette = 0.2f, grain = 0.25f, ops = {
        listOf(
            Ops.warmth(0.45f), Ops.tint(0.16f), Ops.saturation(1.12f),
            Ops.curves(
                master = Curve.of(0, 10, 64, 60, 192, 202, 255, 250),
                b = Curve.of(0, 8, 128, 112, 255, 226),
            ),
            Ops.splitTone(0x806030, 0.08f, 0xFFC860, 0.14f),
        )
    }),

    /** 低饱和正片（经典铬色）：硬朗影调、低饱和、蓝色偏青。 */
    CHROME("chrome", R.string.image_filter_preset_chrome, FilterGroup.FILM, ops = {
        listOf(
            Ops.saturation(0.68f),
            Ops.mix(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0.12f, 0.88f),
            Ops.curves(master = Curve.of(0, 6, 60, 48, 128, 128, 196, 206, 255, 250)),
            Ops.splitTone(0x3C7080, 0.12f, 0xF0D8B0, 0.06f),
        )
    }),

    /** 怀旧负片（经典负片）：青绿暗部、偏红的亮部、硬对比。 */
    CLASSIC_NEG("classic_neg", R.string.image_filter_preset_classic_neg, FilterGroup.FILM, grain = 0.15f, ops = {
        listOf(
            Ops.saturation(0.84f),
            Ops.curves(
                master = Curve.of(0, 12, 64, 54, 192, 206, 255, 246),
                r = Curve.of(0, 0, 128, 124, 255, 255),
                g = Curve.of(0, 10, 128, 130, 255, 244),
            ),
            Ops.splitTone(0x2E8070, 0.22f, 0xF0A890, 0.1f),
        )
    }),

    /** CCD（Dazz 数码相机）：冷调、硬对比、高光早早溢出、微洋红，像千禧年卡片机直出。 */
    CCD("ccd", R.string.image_filter_preset_ccd, FilterGroup.FILM, vignette = 0.15f, grain = 0.12f, ops = {
        listOf(
            Ops.exposure(0.06f), Ops.warmth(-0.22f), Ops.tint(0.1f), Ops.saturation(1.08f),
            Ops.curves(master = Curve.of(0, 0, 56, 42, 160, 176, 234, 252, 255, 255)),
        )
    }),

    /** 拍立得：褪色、青色暗部、暖色高光、明显暗角。 */
    INSTANT("instant", R.string.image_filter_preset_instant, FilterGroup.FILM, vignette = 0.35f, grain = 0.1f, ops = {
        listOf(
            Ops.exposure(0.1f), Ops.fade(0.3f), Ops.saturation(0.85f),
            Ops.splitTone(0x30A0B0, 0.2f, 0xFFD090, 0.14f),
        )
    }),

    /** 复古胶卷（Dazz CPM35）：暖、褪色、黄绿暗部、颗粒感重。 */
    RETRO_35("retro_35", R.string.image_filter_preset_retro_35, FilterGroup.FILM, vignette = 0.25f, grain = 0.45f, ops = {
        listOf(
            Ops.warmth(0.3f), Ops.fade(0.22f), Ops.saturation(0.9f),
            Ops.curves(master = Curve.of(0, 10, 64, 58, 192, 200, 255, 244)),
            Ops.splitTone(0x8A9050, 0.14f, 0xFFD8A0, 0.08f),
        )
    }),

    /** 钨丝灯（灯光片）：整体偏蓝、亮部回暖、夜景电影感。 */
    TUNGSTEN("tungsten", R.string.image_filter_preset_tungsten, FilterGroup.FILM, grain = 0.3f, ops = {
        listOf(
            Ops.warmth(-0.65f), Ops.tint(0.06f),
            Ops.curves(master = Curve.of(0, 8, 64, 56, 192, 204, 255, 252)),
            Ops.splitTone(0x2050A0, 0.12f, 0xFF9860, 0.22f),
        )
    }),

    // ── 电影氛围 ─────────────────────────────────────────────

    /** 电影青橙：暗部青、亮部橙，S 形对比。 */
    TEAL_ORANGE("teal_orange", R.string.image_filter_preset_teal_orange, FilterGroup.CINEMA, vignette = 0.15f, ops = {
        listOf(
            Ops.curves(master = Curve.of(0, 4, 64, 54, 192, 206, 255, 252)),
            Ops.splitTone(0x00A0B0, 0.4f, 0xFF9040, 0.3f),
            Ops.saturation(1.05f),
        )
    }),

    /** 氛围感：压暗、暖高光冷暗部、低饱和、轻褪色。 */
    MOODY("moody", R.string.image_filter_preset_moody, FilterGroup.CINEMA, vignette = 0.3f, ops = {
        listOf(
            Ops.exposure(-0.18f), Ops.saturation(0.74f), Ops.fade(0.15f),
            Ops.splitTone(0x405878, 0.2f, 0xE8B080, 0.14f),
            Ops.curves(master = Curve.of(0, 0, 80, 70, 200, 204, 255, 240)),
        )
    }),

    /** 港风：暖红、绿色暗部、高对比高饱和，九十年代港片。 */
    HONG_KONG("hong_kong", R.string.image_filter_preset_hong_kong, FilterGroup.CINEMA, vignette = 0.2f, grain = 0.2f, ops = {
        listOf(
            Ops.warmth(0.3f), Ops.saturation(1.15f),
            Ops.curves(master = Curve.of(0, 6, 64, 50, 192, 210, 255, 250)),
            Ops.splitTone(0x30804A, 0.22f, 0xFF7050, 0.12f),
        )
    }),

    /** 霓虹：洋红与青的赛博夜色。 */
    NEON("neon", R.string.image_filter_preset_neon, FilterGroup.CINEMA, ops = {
        listOf(
            Ops.contrast(1.1f), Ops.saturation(1.25f),
            Ops.splitTone(0x00C8FF, 0.3f, 0xFF40C0, 0.25f),
        )
    }),

    /** 鲜艳：对比 + 饱和，插画色彩更跳。 */
    VIVID("vivid", R.string.image_filter_preset_vivid, FilterGroup.CINEMA, ops = {
        listOf(Ops.contrast(1.08f), Ops.vibrance(0.3f), Ops.saturation(1.2f))
    }),

    // ── 黑白 ────────────────────────────────────────────────

    MONO("mono", R.string.image_filter_preset_mono, FilterGroup.MONO, ops = { listOf(Ops.grayscale) }),

    /** 高反差黑白：深黑亮白 + 暗角。 */
    NOIR("noir", R.string.image_filter_preset_noir, FilterGroup.MONO, vignette = 0.35f, grain = 0.2f, ops = {
        listOf(
            Ops.grayscale,
            Ops.curves(master = Curve.of(0, 0, 70, 42, 128, 128, 186, 214, 255, 255)),
        )
    }),

    /** 银盐黑白：柔和中间调、微暖的相纸色、细颗粒。 */
    SILVER("silver", R.string.image_filter_preset_silver, FilterGroup.MONO, grain = 0.35f, ops = {
        listOf(
            Ops.grayscale,
            Ops.curves(master = Curve.of(0, 14, 64, 64, 192, 198, 255, 248)),
            Ops.splitTone(0x605040, 0.06f, 0xFFF0D8, 0.06f),
        )
    }),

    /** 怀旧：经典棕褐。 */
    SEPIA("sepia", R.string.image_filter_preset_sepia, FilterGroup.MONO, ops = { listOf(Ops.sepia) });

    companion object {
        fun fromKey(key: String?): FilterPreset = entries.firstOrNull { it.key == key } ?: ORIGINAL
    }
}

/** 手动调节项。[bipolar] 的滑条从中间往两边拉（−100…100），否则 0…100。 */
enum class FilterAdjust(@StringRes val label: Int, val bipolar: Boolean) {
    BRIGHTNESS(R.string.image_filter_adjust_brightness, bipolar = true),
    CONTRAST(R.string.image_filter_adjust_contrast, bipolar = true),
    SATURATION(R.string.image_filter_adjust_saturation, bipolar = true),
    WARMTH(R.string.image_filter_adjust_warmth, bipolar = true),
    TINT(R.string.image_filter_adjust_tint, bipolar = true),
    HIGHLIGHTS(R.string.image_filter_adjust_highlights, bipolar = true),
    SHADOWS(R.string.image_filter_adjust_shadows, bipolar = true),
    FADE(R.string.image_filter_adjust_fade, bipolar = false),
    VIGNETTE(R.string.image_filter_adjust_vignette, bipolar = false),
    GRAIN(R.string.image_filter_adjust_grain, bipolar = false),
}

/**
 * 页面上的全部编辑状态。[intensity] 与 [adjust] 都是滑条上的整数（强度 0–100，调节 −100–100），
 * 整数存储让「同一组参数」可以直接比较、可以存进 SavedState，导出缓存也按它判断是否要重算。
 */
data class FilterState(
    val preset: FilterPreset = FilterPreset.ORIGINAL,
    val intensity: Int = 100,
    val adjust: List<Int> = List(FilterAdjust.entries.size) { 0 },
) {
    operator fun get(a: FilterAdjust): Int = adjust[a.ordinal]

    fun with(a: FilterAdjust, value: Int): FilterState =
        copy(adjust = adjust.toMutableList().also { it[a.ordinal] = value })

    val isModified: Boolean
        get() = (preset != FilterPreset.ORIGINAL && intensity > 0) || adjust.any { it != 0 }

    private val strength: Float get() = if (preset == FilterPreset.ORIGINAL) 0f else intensity / 100f

    /** 手动调节里的颜色部分（暗角、颗粒是空间效果，单独交给 native），按修图 App 的惯常顺序。 */
    fun adjustOps(): List<ColorOp> = buildList {
        fun v(a: FilterAdjust) = this@FilterState[a] / 100f
        if (this@FilterState[FilterAdjust.BRIGHTNESS] != 0) add(Ops.brightness(v(FilterAdjust.BRIGHTNESS)))
        if (this@FilterState[FilterAdjust.CONTRAST] != 0) {
            // 往低拉收得慢一点：−100 时还留 40% 对比，不会糊成一片灰。
            val c = v(FilterAdjust.CONTRAST)
            add(Ops.contrast(if (c > 0) 1f + c * 0.7f else 1f + c * 0.6f))
        }
        if (this@FilterState[FilterAdjust.HIGHLIGHTS] != 0) add(Ops.highlights(v(FilterAdjust.HIGHLIGHTS)))
        if (this@FilterState[FilterAdjust.SHADOWS] != 0) add(Ops.shadows(v(FilterAdjust.SHADOWS)))
        if (this@FilterState[FilterAdjust.SATURATION] != 0) add(Ops.saturation(1f + v(FilterAdjust.SATURATION)))
        if (this@FilterState[FilterAdjust.WARMTH] != 0) add(Ops.warmth(v(FilterAdjust.WARMTH)))
        if (this@FilterState[FilterAdjust.TINT] != 0) add(Ops.tint(v(FilterAdjust.TINT)))
        if (this@FilterState[FilterAdjust.FADE] != 0) add(Ops.fade(v(FilterAdjust.FADE)))
    }

    /** 颜色部分是否等同原图：是的话 native 跳过查表。 */
    val colorIsIdentity: Boolean get() = strength == 0f && adjustOps().isEmpty()

    /** 最终暗角强度 0–1：滤镜自带的按强度缩放，再叠上手动调节。 */
    fun vignette(): Float = (preset.vignette * strength + this[FilterAdjust.VIGNETTE] / 100f).coerceIn(0f, 1f)

    /** 最终颗粒强度 0–1。 */
    fun grain(): Float = (preset.grain * strength + this[FilterAdjust.GRAIN] / 100f).coerceIn(0f, 1f)

    /** 滤镜部分的强度 0–1（原图恒为 0）。 */
    fun presetStrength(): Float = strength

    /**
     * 成品文件名里标识这组参数的那一截。纯滤镜满强度就是滤镜 key（`cream`），改过强度或调节再带 6 位参数指纹
     * （`cream_3fa2c1`）：同名必是同一张图，用户的「跳过已存在」跳得对，「替换」也不会把另一组参数的成品盖掉。
     * 指纹用 String.hashCode（算法由规范固定），跨进程、跨版本稳定。
     */
    fun fileTag(): String {
        // 原图没有强度可言，强度滑条的残值不该让两张一样的原图起不同的名字。
        val effectiveIntensity = if (preset == FilterPreset.ORIGINAL) 100 else intensity
        if (effectiveIntensity == 100 && adjust.all { it == 0 }) return preset.key
        val fingerprint = "$effectiveIntensity:${adjust.joinToString(",")}".hashCode()
        return preset.key + "_" + Integer.toHexString(fingerprint).padStart(8, '0').take(6)
    }
}

/**
 * 把调色流水线采样成 3D LUT（[SIZE]³ 个格点 × RGB，0–1，r 变化最快），以及暗角曲线。
 * 预设的格点结果按 key 缓存最近几只：拖强度 / 调节滑条时只重算「插值 + 调节」，不重跑整条滤镜。
 */
object FilterLut {

    const val SIZE = 33

    /** native 侧按 d² 查暗角乘子的精度。 */
    const val VIGNETTE_LUT_SIZE = 1024

    private const val VIGNETTE_INNER = 0.35f
    private const val VIGNETTE_MAX_DARKNESS = 0.72f

    private val presetCache = object : LinkedHashMap<FilterPreset, FloatArray>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FilterPreset, FloatArray>?) = size > 3
    }

    /** 恒等 LUT：每个格点就是自己的坐标。 */
    val identity: FloatArray by lazy { sample(emptyList()) }

    /** 只采样一只预设（滤镜条缩略图用，满强度、不带调节）。不进缓存，免得一次挤掉正在用的。 */
    fun forPreset(preset: FilterPreset): FloatArray? =
        if (preset == FilterPreset.ORIGINAL) null else synchronized(presetCache) { presetCache[preset] } ?: sample(preset.ops())

    /** 整个状态的 LUT；颜色等同原图时返回 null。 */
    fun forState(state: FilterState): FloatArray? {
        if (state.colorIsIdentity) return null
        val t = state.presetStrength()
        val base = if (t > 0f) {
            val grid = synchronized(presetCache) { presetCache[state.preset] }
                ?: sample(state.preset.ops()).also { synchronized(presetCache) { presetCache[state.preset] = it } }
            if (t >= 1f) grid else {
                val id = identity
                FloatArray(grid.size) { id[it] + (grid[it] - id[it]) * t }
            }
        } else {
            identity
        }
        val ops = state.adjustOps()
        if (ops.isEmpty()) return base
        val out = base.copyOf()
        val c = FloatArray(3)
        for (i in out.indices step 3) {
            c[0] = out[i]; c[1] = out[i + 1]; c[2] = out[i + 2]
            for (op in ops) op.apply(c)
            out[i] = c[0].coerceIn(0f, 1f); out[i + 1] = c[1].coerceIn(0f, 1f); out[i + 2] = c[2].coerceIn(0f, 1f)
        }
        return out
    }

    /** 在 [SIZE]³ 个格点上跑一遍 [ops]，结果夹回 0–1。 */
    fun sample(ops: List<ColorOp>): FloatArray {
        val n = SIZE
        val out = FloatArray(n * n * n * 3)
        val c = FloatArray(3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            c[0] = r / (n - 1f); c[1] = g / (n - 1f); c[2] = b / (n - 1f)
            for (op in ops) op.apply(c)
            out[i++] = c[0].coerceIn(0f, 1f)
            out[i++] = c[1].coerceIn(0f, 1f)
            out[i++] = c[2].coerceIn(0f, 1f)
        }
        return out
    }

    /** 距中心 [d]（半对角线归一化，四角 = 1）处的暗化比例：smoothstep 过渡，没有可见的环。 */
    fun vignetteDarkness(d: Float, strength: Float): Float {
        if (strength <= 0f) return 0f
        val t = ((d - VIGNETTE_INNER) / (1f - VIGNETTE_INNER)).coerceIn(0f, 1f)
        return VIGNETTE_MAX_DARKNESS * strength.coerceAtMost(1f) * t * t * (3f - 2f * t)
    }

    /** 按 d² 均匀采样的颜色乘子（1 = 不变暗）；强度为 0 返回 null。用 d² 做下标，native 不用开方。 */
    fun vignette(strength: Float): FloatArray? {
        if (strength <= 0f) return null
        return FloatArray(VIGNETTE_LUT_SIZE + 1) { i ->
            1f - vignetteDarkness(kotlin.math.sqrt(i / VIGNETTE_LUT_SIZE.toFloat()), strength)
        }
    }
}
