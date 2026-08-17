package ceui.pixiv.feeds

import android.content.Context
import android.util.TypedValue
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.res.use
import androidx.fragment.app.Fragment

/**
 * 宿主 app 注入给列表框架的那几件「本模块无从知晓」的事。
 *
 * 本模块对 pixiv、对宿主的设计系统、对宿主用哪个 toast 库、对宿主怎么感知网络，统统一无所知，
 * 但 [FeedFragment] 又确实要画出符合宿主主题的刷新圈和空态、要把加载失败说成人话。这些是
 * **进程级、与具体页面无关**的东西，所以做成一个装一次的委托，而不是 [FeedFragment] 上的
 * protected 钩子——全仓上百个列表页都直接继承 [FeedFragment]，做成钩子等于让每个页面各写一遍。
 *
 * 所有方法都有默认实现（纯 framework attr / 系统 Toast / 不接网络），不装也能跑，只是长得
 * 像个没上妆的 AOSP 列表。宿主在 Application.onCreate 里 [FeedFramework.install] 一次即可。
 *
 * ⚠️ 实现会被进程级持有：**不要捕获 Activity / Fragment / View**，需要 Context 就用传进来的那个。
 */
interface FeedHost {

    /**
     * 列表骨架的配色。每次 onViewCreated 现取（[context] 是当前 Activity），所以切主题 /
     * 日夜切换重建 Activity 后自动跟上，实现方不必自己监听。
     */
    fun theme(context: Context): FeedTheme = FeedTheme.fromThemeAttrs(context)

    /**
     * 空态插画。返回 0 = 不画插画（默认）。错误态用的是框架自带的 [R.drawable.ic_feed_error]，
     * 不走这里——那张图是框架语义的一部分（「加载失败，点击重试」），而「空」长什么样属于宿主的调性。
     */
    @DrawableRes
    fun emptyStateImage(context: Context): Int = 0

    /**
     * 异常 → 人话。返回 null（默认）时框架退回自带的通用文案。
     *
     * 实现里**不要抛**：这条路上已经有一个异常了，第二个会把错误页本身弄崩。框架侧有
     * runCatching 兜底，但那是兜底不是许可。
     */
    fun humanReadableError(context: Context, throwable: Throwable): String? = null

    /** 「屏幕上有内容兜底、但刷新失败了」的一次性轻提示。默认系统 Toast。 */
    fun showMessage(context: Context, message: CharSequence) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 订阅「断网 → 有网」的迁移，用于自动重试错误态。默认不接（不订阅任何东西）。
     *
     * 在 [Fragment.onViewCreated] 里被调用，实现方应绑 `fragment.viewLifecycleOwner` 自动解绑。
     * 只在**真正发生迁移**时回调 [onRestored]：注册瞬间的粘性首发（LiveData 会立刻回放当前值）
     * 必须跳过，否则每次进页面都白重试一次。
     */
    fun observeNetworkRestored(fragment: Fragment, onRestored: () -> Unit) {}

    /**
     * 是否建议在全屏错误态补「去网络测试」入口。只对网络类错误（断网 / 超时 / SSL）
     * 返回 true：这类错误重试大概率仍失败，用户需要去诊断页而不是反复点重试。
     * 默认 false，不装宿主就不显示。
     */
    fun shouldSuggestNetworkTest(context: Context, throwable: Throwable): Boolean = false

    /**
     * 打开宿主提供的网络诊断页。默认 no-op；[shouldSuggestNetworkTest] 返回 true 的宿主
     * 必须给出可用的跳转实现。与 [shouldSuggestNetworkTest] 成对出现，
     * 避免本模块反向依赖宿主 App 的页面路由。
     */
    fun openNetworkTest(context: Context) {}
}

/**
 * [FeedFragment] 画骨架要用的三个颜色。是**算好的色值**而不是 `@ColorRes`：宿主的主题派生色
 *（从 colorPrimary 现算出的可读强调色等）表达不成色值资源。
 */
data class FeedTheme(
    /** 裸 `fragment_feed` 形态下刷给列表根的底色。 */
    @ColorInt val rootBackground: Int,
    /** 下拉刷新箭头 + 空态插画 tint 的强调色。 */
    @ColorInt val accent: Int,
    /** 下拉刷新那块小圆饼的底色。必须与 [accent] 恒有对比度，否则暗色模式下刷出一块白饼。 */
    @ColorInt val spinnerTrack: Int,
) {
    companion object {
        /**
         * 不装 [FeedHost] 时的兜底：一律取 framework attr，绝大多数主题都解析得出，也就不必为了
         * 一个默认值让本模块依赖 appcompat / material。宿主装了自己的实现就不会走到这里。
         */
        fun fromThemeAttrs(context: Context): FeedTheme = FeedTheme(
            rootBackground = resolveColor(context, android.R.attr.colorBackground, FALLBACK_SURFACE),
            accent = resolveColor(context, android.R.attr.colorAccent, FALLBACK_ACCENT),
            spinnerTrack = resolveColor(
                context, android.R.attr.colorBackgroundFloating, FALLBACK_SURFACE,
            ),
        )

        /**
         * 走 [android.content.res.TypedArray.getColor] 而不是 `theme.resolveAttribute` +
         * `TypedValue.data`，两个静默出错的口子都由它堵掉：
         * - attr 在主题里压根没定义（`colorBackgroundFloating` 是 API 23 才有、且只有 Material
         *   系主题给值）→ resolveAttribute 返回 false 而 [TypedValue] 是全新的，`data` 就是 0，
         *   即 `#00000000`；
         * - attr 指向的是一份 ColorStateList XML（TYPE_STRING）→ `data` 是字符串池下标，当色值
         *   用就是任意垃圾色。
         *
         * 两种情况下列表根都会被刷成透明，宿主布局的装饰背景整页透出来——正是
         * [FeedFragment.feedRootBackgroundColor] 的存在理由本身。所以 [fallback] 一律取不透明色：
         * 兜底色不好看是小事，透明是 bug。
         */
        @ColorInt
        private fun resolveColor(context: Context, attr: Int, @ColorInt fallback: Int): Int =
            context.obtainStyledAttributes(intArrayOf(attr)).use { it.getColor(0, fallback) }

        /** attr 都拿不到时的最后兜底，对齐 AOSP Material Light 的 colorBackground。 */
        @ColorInt
        private val FALLBACK_SURFACE: Int = 0xFFFAFAFA.toInt()

        /** 同上；压在 [FALLBACK_SURFACE] 上对比度足够，箭头和插画不会隐形。 */
        @ColorInt
        private val FALLBACK_ACCENT: Int = 0xFF3F51B5.toInt()
    }
}

/** 列表框架的进程级装配点。 */
object FeedFramework {

    /**
     * 读在主线程（[FeedFragment] 的各回调），写在 Application.onCreate。@Volatile 只为保证
     * 「装过之后任何线程都读得到装好的那个」，不做别的同步——它只被赋值一次。
     */
    @Volatile
    private var installed: FeedHost = object : FeedHost {}

    internal val host: FeedHost
        get() = installed

    /**
     * 装上宿主实现。必须在**任何列表页创建之前**调用（Application.onCreate 里），否则先建
     * 出来的页面会拿到默认实现，画出一张不合主题的空态。重复调用以最后一次为准。
     */
    fun install(host: FeedHost) {
        installed = host
    }
}
