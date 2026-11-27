package ceui.pixiv.ui.translate

import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Local
import ceui.pixiv.i18n.AppLocales
import ceui.pixiv.witstudio.dialog.WitDialog
import java.util.Locale

/**
 * 翻译目标语言：优先使用用户在「设置 → AI 功能 → 翻译目标语言」中显式指定的语言（#1401）；
 * 未指定（空串 = 跟随应用语言）时回落到 **app 内语言** ([AppLocales.currentLocale])。
 *
 * app 自带语言选择器([AppLocales]),系统语言和 app 语言可能不一致(比如系统日语、app 选中文),
 * 跟随应用语言时一律以 app 语言为准。跟随系统时 [AppLocales.currentLocale] 已经把系统 locale
 * 映射进支持集了,这里直接用它的结果。
 */
fun appTranslateTargetLang(): String =
    configuredTranslateTargetLang() ?: translateTargetLangOf(AppLocales.currentLocale())

/**
 * 用户显式配置的翻译目标语言（已归一到 [AppLocales.supportedTags]）；
 * `null` 表示未单独指定，跟随应用语言。
 */
fun configuredTranslateTargetLang(): String? =
    normalizeConfiguredTranslateTargetLang(Shaft.sSettings?.translateTargetLanguage)

/**
 * 纯函数版本：合并显式配置与回落 [Locale]，供单测直接覆盖而无需初始化 MMKV / Settings。
 */
internal fun resolveTranslateTargetLang(configured: String?, fallbackLocale: Locale): String =
    normalizeConfiguredTranslateTargetLang(configured) ?: translateTargetLangOf(fallbackLocale)

/**
 * 把持久化里的目标语言码归一为受支持的规范值；空串、格式非法或不在支持集内时返回 `null`
 * （交由调用方回落跟随应用语言，避免把非法码发给服务端触发 400 或误落英语）。
 */
internal fun normalizeConfiguredTranslateTargetLang(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val locale = Locale.forLanguageTag(trimmed.replace('_', '-'))
    if (locale.language.isEmpty()) return null
    val normalized = translateTargetLangOf(locale)
    return normalized.takeIf { it in SUPPORTED_TRANSLATE_TARGET_LANGS }
}

/** 展示给用户的当前翻译目标语言文案；未指定时显示「跟随应用语言」。 */
@JvmOverloads
fun translateTargetLanguageDisplayName(
    context: Context,
    tag: String? = configuredTranslateTargetLang(),
): String {
    val normalized = normalizeConfiguredTranslateTargetLang(tag)
    return if (normalized == null) {
        context.getString(R.string.translate_target_language_follow_app)
    } else {
        AppLocales.displayName(normalized)
    }
}

/** 弹出翻译目标语言单选框（首位为「跟随应用语言」，其后与 [AppLocales.supportedTags] 对齐）。 */
@JvmOverloads
fun showTranslateTargetLanguagePicker(
    context: Context,
    onChanged: ((String?) -> Unit)? = null,
) {
    val tags = buildList<String?> {
        add(null)
        addAll(AppLocales.supportedTags)
    }
    val labels = tags.map { tag ->
        if (tag == null) context.getString(R.string.translate_target_language_follow_app)
        else AppLocales.displayName(tag)
    }
    val current = configuredTranslateTargetLang()
    val checkedIndex = if (current == null) 0 else tags.indexOf(current).coerceAtLeast(0)
    WitDialog.CheckableDialogBuilder(context)
        .setTitle(R.string.translate_target_language_title)
        .setCheckedIndex(checkedIndex)
        .addItems(labels.toTypedArray()) { dialog, which ->
            val picked = tags[which]
            val next = picked.orEmpty()
            val settings = Shaft.sSettings
            if (settings != null && settings.translateTargetLanguage != next) {
                settings.translateTargetLanguage = next
                Local.setSettings(settings)
                onChanged?.invoke(picked)
            }
            dialog.dismiss()
        }
        .show()
}

/**
 * [Locale] → Google gtx 端点的 `tl` 取值。
 *
 * app 支持集 en/zh-CN/zh-TW/ja/ko/ru/tr/id 基本和 Google 的语言代码一一对得上,只有中文要按繁简分流,
 * 印尼语要把旧码 `in` 归一成 `id`。
 * 抽成不依赖 [AppLocales] 的纯函数,好让单测能直接覆盖各种 locale 形态。
 */
internal fun translateTargetLangOf(locale: Locale): String {
    // 大小写一律钉 Locale.ROOT:app 支持土耳其语,而土耳其 locale 下 i/I 的大小写映射是特殊的
    // (i→İ、I→ı),用默认 locale 规则处理语言/地区码迟早出事。
    val language = locale.language.lowercase(Locale.ROOT)
    if (language.isEmpty()) return "en"
    // 部分 Android 版本的 Locale 会把印尼语报成 ISO 旧码 in,Google 只认 id。
    if (language == "in") return "id"
    if (language != "zh") return language

    // 繁简分流要同时看 script 和地区:zh-Hant-* 以及 zh-TW / zh-HK / zh-MO 都是繁体。
    // app 支持集里的 zh-TW 只带 country 不带 script,跟随系统时拿到的系统 locale 又可能是
    // zh-Hant-HK 这种只认 script 的形态,两条都得认。
    val traditional = locale.script.equals("Hant", ignoreCase = true) ||
        locale.country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS
    return if (traditional) "zh-TW" else "zh-CN"
}

private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
private val SUPPORTED_TRANSLATE_TARGET_LANGS = AppLocales.supportedTags.toSet()
