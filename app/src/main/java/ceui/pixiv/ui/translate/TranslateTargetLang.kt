package ceui.pixiv.ui.translate

import ceui.pixiv.i18n.AppLocales
import java.util.Locale

/**
 * 翻译目标语言 = **app 内语言**,不是系统语言。
 *
 * app 自带语言选择器([AppLocales]),用户在 app 里选了俄语就该拿到俄语译文;系统语言和 app 语言
 * 可能不一致(比如系统日语、app 选中文),一律以 app 语言为准。跟随系统时 [AppLocales.currentLocale]
 * 已经把系统 locale 映射进支持集了,这里直接用它的结果。
 */
fun appTranslateTargetLang(): String = translateTargetLangOf(AppLocales.currentLocale())

/**
 * [Locale] → Google gtx 端点的 `tl` 取值。
 *
 * app 支持集 en/zh-CN/zh-TW/ja/ko/ru/tr 正好和 Google 的语言代码一一对得上,只有中文要按繁简分流。
 * 抽成不依赖 [AppLocales] 的纯函数,好让单测能直接覆盖各种 locale 形态。
 */
internal fun translateTargetLangOf(locale: Locale): String {
    // 大小写一律钉 Locale.ROOT:app 支持土耳其语,而土耳其 locale 下 i/I 的大小写映射是特殊的
    // (i→İ、I→ı),用默认 locale 规则处理语言/地区码迟早出事。
    val language = locale.language.lowercase(Locale.ROOT)
    if (language.isEmpty()) return "en"
    if (language != "zh") return language

    // 繁简分流要同时看 script 和地区:zh-Hant-* 以及 zh-TW / zh-HK / zh-MO 都是繁体。
    // app 支持集里的 zh-TW 只带 country 不带 script,跟随系统时拿到的系统 locale 又可能是
    // zh-Hant-HK 这种只认 script 的形态,两条都得认。
    val traditional = locale.script.equals("Hant", ignoreCase = true) ||
        locale.country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS
    return if (traditional) "zh-TW" else "zh-CN"
}

private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
