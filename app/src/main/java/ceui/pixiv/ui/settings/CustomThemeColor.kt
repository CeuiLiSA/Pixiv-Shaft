package ceui.pixiv.ui.settings

import android.content.Context
import android.content.res.loader.ResourcesLoader
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import com.google.android.material.color.ShaftColorResourcesLoader

/**
 * 「自定义主题色」——[ThemeColorCatalog] 那十个预设之外的第十一档，用户自己给一个 HEX（issue #1014）。
 *
 * ## 它是怎么落到界面上的
 *
 * 主题色的唯一权威是 `?attr/colorPrimary`（全仓 200+ 处 XML 直接引它，[ceui.pixiv.witstudio.theme.V3Palette]
 * 整套派生色也是从这个 attr 解出来的）。theme attr 的值编译期就烤进 `AppTheme.IndexN` 了，
 * 运行时改不了 —— 所以自定义色走这条路：
 *
 * 1. `AppTheme.Custom` 的 colorPrimary 不写字面量，指向 `@color/custom_theme_primary`；
 * 2. 进程启动 / Activity onCreate 时，用 `ResourcesLoader` 把这个 color **资源**覆盖成用户的色值
 *    （[applyResourceOverride]，必须在 `setTheme` 之前调，theme attr 是那一刻解析的）；
 * 3. 之后 `?attr/colorPrimary` 解出来的就是用户的色，全 app 无差别生效，不用改任何一处 XML。
 *
 * ## 为什么要 Android 11
 *
 * `ResourcesLoader` 是 API 30 才有的，也是 Android 唯一一个「运行时换资源值」的官方口子
 * （Material 自己的 dynamic color 同样在 R 以下直接放弃，见 `ColorResourcesOverride.getInstance`）。
 * 所以 [isSupported] 为 false 时「自定义」这一行根本不出现在主题色列表里 —— 与其给个只染一半
 * 界面的半吊子（`Shaft.getThemeColor()` 是用户的色、`?attr/colorPrimary` 还是预设色），不如不给。
 *
 * ## 存储
 *
 * `Settings.themeIndex == `[INDEX]` (-1)` 表示「用自定义色」，色值存在 `Settings.customThemeColor`。
 * -1 落在 [ThemeColorCatalog] 的越界回落分支里，老代码路径（含旧版本 app 读到这份 settings）
 * 一律回落 0 号，不会崩也不会花屏。
 */
object CustomThemeColor {

    /** `Settings.themeIndex` 的自定义档位。刻意取负数，与预设的 0..9 永不相撞。 */
    const val INDEX = -1

    @JvmStatic
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 当前存着的自定义色；没设过或存的字符串非法时返回 null。 */
    @JvmStatic
    @ColorInt
    fun savedColor(): Int? = normalize(Shaft.sSettings?.customThemeColor)?.let(Color::parseColor)

    /** 自定义档是否真的生效：档位选中 + 系统支持 + 色值有效，三者缺一就回落预设。 */
    @JvmStatic
    fun isActive(): Boolean =
        isSupported && Shaft.sSettings?.themeIndex == INDEX && savedColor() != null

    /**
     * 当前主题色的 `#RRGGBB`。[ceui.lisa.activities.Shaft.getThemeColor] 的实现体 ——
     * 自定义档没生效时回落到预设目录，与 `updateTheme` 的回落规则保持同义。
     */
    @JvmStatic
    fun currentHex(): String {
        val custom = if (isActive()) savedColor() else null
        return custom?.let(::toHex) ?: ThemeColorCatalog.hexOf(Shaft.sSettings.themeIndex)
    }

    /**
     * 把 `@color/custom_theme_primary` / `_dark` 换成用户的色值。**必须在 `setTheme` 之前调**。
     * Android 11 以下直接 no-op（[isSupported]），调用方无需自己判断。
     */
    @JvmStatic
    fun applyResourceOverride(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val color = savedColor() ?: return
        LoaderCache.applyTo(context, color)
    }

    /**
     * ResourcesLoader 相关的一切关进这个 R+ 专用的嵌套 object：它的字段类型本身就是 API 30 的类，
     * 单独成类才能保证低版本机器上永远不加载、不触发校验。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private object LoaderCache {

        /** 按色值缓存：ARSC 是运行时手写出来的二进制表，每个 Activity 重造一遍纯属浪费。 */
        private var loader: ResourcesLoader? = null
        private var loadedColor: Int = 0

        fun applyTo(context: Context, @ColorInt color: Int) {
            val resources = context.resources
            // 幂等：已经是目标色就别再叠 loader —— 同一个 Resources 重复 addLoaders 会堆一串等价项
            if (resources.getColor(R.color.custom_theme_primary, null) == color) return
            val cached = loader?.takeIf { loadedColor == color }
            // 显式 HashMap 而不是 mapOf(id to color)：Pair 里一个是资源 id、一个带 @ColorInt，
            // Kotlin 把这俩推成同一个类型参数后 lint 的 ResourceAsColor 会误报「别拿资源 id 当色值」。
            val overrides = HashMap<Int, Int>(2)
            overrides[R.color.custom_theme_primary] = color
            overrides[R.color.custom_theme_primary_dark] = darkVariantOf(color)
            val ready = cached ?: ShaftColorResourcesLoader.create(context, overrides) ?: return
            loader = ready
            loadedColor = color
            resources.addLoaders(ready)
        }
    }

    /**
     * colorPrimaryDark（状态栏/QMUI 少数控件用）。预设里这一对是设计师手挑的，比例在 0.72~0.88
     * 之间飘；自定义色没法手挑，统一压 22% 黑，落在那个区间里。
     */
    @JvmStatic
    @ColorInt
    fun darkVariantOf(@ColorInt color: Int): Int = ColorUtils.blendARGB(color, Color.BLACK, 0.22f)

    /**
     * 用户输入 → 规范 `#RRGGBB`（大写）。接受带不带 `#`、3 位缩写、大小写混写；
     * **不接受 8 位 ARGB** —— 半透明的 colorPrimary 会把所有拿它当实底的控件染穿。
     * 非法一律 null，由调用方决定提示还是回落。
     */
    @JvmStatic
    fun normalize(input: String?): String? {
        val raw = input?.trim()?.removePrefix("#")?.uppercase() ?: return null
        if (raw.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return when (raw.length) {
            3 -> "#" + raw.map { "$it$it" }.joinToString("")
            6 -> "#$raw"
            else -> null
        }
    }

    @JvmStatic
    fun toHex(@ColorInt color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}
