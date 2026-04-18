package ceui.pixiv.i18n

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
    )

    fun displayName(tag: String): String =
        displayNames[tag] ?: Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it) }

    private const val CONFIGURED_KEY = "app_locale_configured"

    private val supportedLocales: List<Locale> by lazy {
        supportedTags.map { Locale.forLanguageTag(it) }
    }

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    /** 用户是否显式配置过语言（包括显式选了"跟随系统"）。 */
    val hasUserConfigured: Boolean get() = mmkv.decodeBool(CONFIGURED_KEY, false)

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
        }
    }

    /**
     * 切换语言。[tag] 为 `null` 或空串 = 跟随系统。AppCompat 会自动 recreate 顶层 Activity。
     * 调用一次后即认为用户已显式配置，不再被 [ensureInitialized] 覆盖。
     */
    fun apply(tag: String?) {
        val list = if (tag.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(list)
        markUserConfigured()
    }

    /** 供 [AppLocalesBootstrap] 在迁移旧字段后调用，表达"这个用户已经有过显式语言偏好"。 */
    internal fun markUserConfigured() {
        mmkv.encode(CONFIGURED_KEY, true)
    }

    /** 当前应用实际生效的 locale（跟随系统时返回系统匹配后的那一个，无匹配时 fallback English）。 */
    fun currentLocale(): Locale {
        val app = AppCompatDelegate.getApplicationLocales()
        if (!app.isEmpty) return app[0] ?: Locale.ENGLISH
        return matchSupported(Locale.getDefault()) ?: Locale.ENGLISH
    }

    /** 当前是否跟随系统。 */
    fun isFollowingSystem(): Boolean = AppCompatDelegate.getApplicationLocales().isEmpty

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
