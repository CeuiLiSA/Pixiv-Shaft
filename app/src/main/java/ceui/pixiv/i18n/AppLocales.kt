package ceui.pixiv.i18n

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.tencent.mmkv.MMKV
import java.util.Locale

/**
 * 应用语言的单一真源：
 * - 存储走 `AppCompatDelegate.setApplicationLocales(...)`（Android 13+ 系统持久化，12- 由 AppCompat backport
 *   通过 `AppLocalesMetadataHolderService` 持久化）。
 * - "跟随系统" = 传空 [LocaleListCompat]。为区分"用户没选过"与"用户选了跟随系统"，额外用 MMKV 记一个布尔标记。
 * - 用户没选过且系统 locale 不在 [supportedTags] 内时，首启显式回落 English（issue #488）。
 */
object AppLocales {

    /** BCP-47 支持集，和 `res/xml/locales_config.xml` 一一对应。 */
    val supportedTags: List<String> = listOf(
        "en",
        "zh-CN",
        "zh-TW",
        "ja",
        "ko",
        "ru",
        "tr",
        "id",
    )

    /**
     * 显示给用户看的语言名（每种语言用自己写的形式）。
     * 不走 [Locale.getDisplayName]，因为它给 zh-CN / zh-TW 带上"中国"/"台灣"等国家后缀。
     */
    private val displayNames: Map<String, String> = mapOf(
        "en" to "English",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "ru" to "Русский",
        "tr" to "Türkçe",
        "id" to "Bahasa Indonesia",
    )

    fun displayName(tag: String): String =
        displayNames[tag] ?: Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it) }

    private const val CONFIGURED_KEY = "app_locale_configured"
    private const val TAG_KEY = "app_locale_tag"  // BCP-47；空串 = 跟随系统

    private val supportedLocales: List<Locale> by lazy {
        supportedTags.map { Locale.forLanguageTag(it) }
    }

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    /** 用户是否显式配置过语言（包括显式选了"跟随系统"）。 */
    val hasUserConfigured: Boolean get() = mmkv.decodeBool(CONFIGURED_KEY, false)

    /**
     * 用户最近一次显式选择的语言 BCP-47 tag。
     * `null` = 未配置过 或 显式选了"跟随系统"。
     * 这是 [ceui.lisa.activities.BaseActivity.attachBaseContext] 用来包 Configuration 的来源 —
     * 它必须由 [saveTag] / [apply] 显式写入，AppCompat 的 per-app locale 持久化 *不会* 自动同步过来。
     */
    fun savedTag(): String? = mmkv.decodeString(TAG_KEY, null)?.takeIf { it.isNotBlank() }

    /**
     * 仅在用户从未显式配置过时才按系统 locale 做合理默认：
     * 系统 locale 在支持集里 → 什么都不做（跟随系统）；否则显式 English。
     * 一旦调过 [apply] 或完成旧字段迁移，本方法就不再主动改动用户设置。
     */
    fun ensureInitialized() {
        if (hasUserConfigured) return
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return
        if (matchSupported(Locale.getDefault()) == null) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            saveTag("en")
        }
    }

    /**
     * 切换语言并触发顶层 Activity recreate —— 设置页那条路径用。
     * [tag] 为 `null` 或空串 = 跟随系统。
     */
    fun apply(tag: String?) {
        saveTag(tag)
        val list = if (tag.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(list)
    }

    /**
     * 仅写持久化 —— 不触发 AppCompat recreate。
     * 配合 [applyConfigurationInPlace] 用：首装语言 onboarding 想原地切换页面不闪屏的场景。
     * 后续新启动的 Activity 在 [ceui.lisa.activities.BaseActivity.attachBaseContext] 里读到这个
     * tag、自动包出正确 Configuration；进程下次冷启时 [AppLocalesBootstrap] 会把 AppCompat 也同步上。
     */
    fun saveTag(tag: String?) {
        mmkv.encode(TAG_KEY, tag.orEmpty())
        markUserConfigured()
    }

    /**
     * 在不 recreate 的前提下，让**当前进程**所有 Resources 立刻按新 locale 解析字符串。
     *
     * - **Activity Resources** — Fragment / Activity 内的 `getString(...)`、dialog 文案、setupTermsText 等
     *   后续取 string 自动用新 locale。已经 inflate 完的 `TextView` 文本不会自动刷新，调用方要自己把
     *   这些视图的 text 再 `getString(R.string.xxx)` 一次塞回去。
     * - **Application Resources** — `Shaft.sApplicationContext.getString(...)`、`Common.showToast(...)` 这类
     *   走 ApplicationContext 取 string 的代码路径也跟着切。否则首装 onboarding 完到下次冷启之前，
     *   全局 toast 文案会停留在系统 locale。
     *
     * 用 [android.content.res.Resources.updateConfiguration]：是的，API 25 起被标为 deprecated，
     * 但这是 Android 平台上唯一让「已 attach 的 Resources」切语言又不 recreate 的入口。
     * `createConfigurationContext` 只能拿到一个新的 Context 副本，不影响原 Resources。
     *
     * 不会触发 AppCompat recreate —— AppCompat 的 lifecycle callback 听的是 `setApplicationLocales`，
     * 不是 `Resources.updateConfiguration`。
     */
    @Suppress("DEPRECATION")
    fun applyConfigurationInPlace(activity: Activity, tag: String?) {
        val locale = resolveLocale(tag)
        Locale.setDefault(locale)  // JVM 默认 locale，给 DateFormat / Number 这类用

        val actRes = activity.resources
        actRes.updateConfiguration(buildLocaleConfig(actRes, locale), actRes.displayMetrics)

        // API 31+ Activity Resources 独立于 Application Resources，要分别刷一次。
        val appRes = activity.applicationContext.resources
        if (appRes !== actRes) {
            appRes.updateConfiguration(buildLocaleConfig(appRes, locale), appRes.displayMetrics)
        }
    }

    private fun buildLocaleConfig(res: android.content.res.Resources, locale: Locale): Configuration {
        val cfg = Configuration(res.configuration)
        cfg.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cfg.setLocales(LocaleList(locale))
        }
        return cfg
    }

    /**
     * 包出一个按 [tag] 解析字符串的 Context；不影响 [base] 自身的 Resources。
     * 适合给独立子树（比如 ViewStub inflate）用。
     */
    fun localizedContext(base: Context, tag: String?): Context {
        val locale = resolveLocale(tag)
        val cfg = localeOnlyOverride(locale)
        return base.createConfigurationContext(cfg)
    }

    /**
     * 只覆盖 locale 的 override Configuration：用 **空** [Configuration]，其余字段全留 UNDEFINED，
     * 交给 [Context.createConfigurationContext] 的 delta 合并从 base 实时继承。
     *
     * 关键：绝不能 `Configuration(base.resources.configuration)` 整份拷贝——那会把当下的 `uiMode`
     * （含日夜位）一并钉死。AppCompat 的 `MODE_NIGHT_FOLLOW_SYSTEM` 解析"系统当前是不是深色"时是拿
     * Application context 的 Resources 去问的，而 Application 进程级唯一、永不重建，一旦它的 uiMode 被
     * 钉死，系统切深浅色时 app 纹丝不动，只有下次冷启才追上（见 [wrapWithSavedLocale] 的复用方）。
     * 空 config 里 uiMode = UI_MODE_*_UNDEFINED(0)，被 delta 合并当作"未 override"跳过，从而继续跟随系统。
     */
    private fun localeOnlyOverride(locale: Locale): Configuration {
        val cfg = Configuration()
        cfg.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cfg.setLocales(LocaleList(locale))
        }
        return cfg
    }

    /**
     * Activity / Application 的 `attachBaseContext` 统一入口：根据 [savedTag] 包一层
     * Configuration，没存就原样返回。整段被 try/catch 包住 —— attachBaseContext 阶段抛异常会让
     * 整个 Activity 起不来，绝对不能因为「语言不对」就把 app 搞崩。
     *
     * 复用方：[ceui.lisa.activities.BaseActivity.attachBaseContext]、
     * [ceui.lisa.activities.Shaft.attachBaseContext]、
     * [ceui.pixiv.ui.slideshow.SlideshowActivity.attachBaseContext]。
     */
    fun wrapWithSavedLocale(base: Context): Context {
        return try {
            val tag = savedTag() ?: return base
            val locale = Locale.forLanguageTag(tag)
            // 只覆盖 locale，uiMode 等其余字段继续跟随系统，否则 MODE_NIGHT_FOLLOW_SYSTEM 会失效，
            // 见 [localeOnlyOverride] 的详解。
            base.createConfigurationContext(localeOnlyOverride(locale))
        } catch (t: Throwable) {
            base
        }
    }

    private fun resolveLocale(tag: String?): Locale =
        if (tag.isNullOrBlank()) {
            matchSupported(Locale.getDefault()) ?: Locale.ENGLISH
        } else {
            Locale.forLanguageTag(tag)
        }

    /** 供 [AppLocalesBootstrap] 在迁移旧字段后调用，表达"这个用户已经有过显式语言偏好"。 */
    internal fun markUserConfigured() {
        mmkv.encode(CONFIGURED_KEY, true)
    }

    /**
     * 当前应用实际生效的 locale（跟随系统时返回系统匹配后的那一个，无匹配时 fallback English）。
     *
     * 优先看 [savedTag]：onboarding 路径只写 MMKV 不写 AppCompatDelegate，要等到下次冷启
     * [AppLocalesBootstrap] 才补同步；这段时间内 AppCompatDelegate 仍是空的，但用户选的语言已经在
     * MMKV 里 + Activity Resources 也已经就地切到位了，必须以 MMKV 为准。
     *
     * 影响范围：[ceui.lisa.helper.LanguageHelper] 拼 `accept-language` header、设置页显示"当前
     * 语言"、`SelectLanguageFragment` 的勾选回显。
     */
    fun currentLocale(): Locale {
        savedTag()?.let { return Locale.forLanguageTag(it) }
        val app = AppCompatDelegate.getApplicationLocales()
        if (!app.isEmpty) return app[0] ?: Locale.ENGLISH
        return matchSupported(Locale.getDefault()) ?: Locale.ENGLISH
    }

    /**
     * 当前是否跟随系统。同 [currentLocale] —— MMKV 是首选真源；MMKV 空（首装未选过 / 或显式选了
     * 「跟随系统」由 [apply] 用空串写入）时再看 AppCompatDelegate。
     */
    fun isFollowingSystem(): Boolean {
        // 显式存了 tag → 不跟随系统
        if (savedTag() != null) return false
        // 显式选过「跟随系统」：MMKV 里 TAG_KEY 是空串、CONFIGURED_KEY 是 true
        if (hasUserConfigured) return true
        // 完全没配置过 → AppCompatDelegate 的状态说了算
        return AppCompatDelegate.getApplicationLocales().isEmpty
    }

    private fun matchSupported(target: Locale): Locale? {
        val language = target.language
        val country = target.country
        // 港澳繁中单独走 zh-TW（否则会被 language-only fallback 落到 zh-CN）。
        if (language == "zh" && (country.equals("HK", true) || country.equals("MO", true))) {
            return supportedLocales.firstOrNull { it.toLanguageTag() == "zh-TW" }
        }
        return supportedLocales.firstOrNull {
            it.language == language && it.country.equals(country, ignoreCase = true)
        } ?: supportedLocales.firstOrNull { it.language == language }
    }
}
