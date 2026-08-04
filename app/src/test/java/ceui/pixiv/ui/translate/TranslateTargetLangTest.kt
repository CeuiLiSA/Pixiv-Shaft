package ceui.pixiv.ui.translate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * [translateTargetLangOf] 的映射校验 —— 覆盖 app 支持集的 7 种语言,外加跟随系统时可能拿到的
 * 各种中文形态。起因是 Google Play 上一条俄语反馈:app 已经是俄语了,图片翻译却仍然出中文。
 */
class TranslateTargetLangTest {

    @After
    fun tearDown() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `app 支持集里的非中文语言原样透传`() {
        assertEquals("ru", translateTargetLangOf(Locale.forLanguageTag("ru")))
        assertEquals("tr", translateTargetLangOf(Locale.forLanguageTag("tr")))
        assertEquals("ja", translateTargetLangOf(Locale.forLanguageTag("ja")))
        assertEquals("ko", translateTargetLangOf(Locale.forLanguageTag("ko")))
        assertEquals("en", translateTargetLangOf(Locale.forLanguageTag("en")))
    }

    @Test
    fun `带地区的非中文 locale 只取语言码`() {
        // 跟随系统时系统 locale 常常带地区,Google 的 tl 认 ru 不认 ru-RU
        assertEquals("ru", translateTargetLangOf(Locale.forLanguageTag("ru-RU")))
        assertEquals("en", translateTargetLangOf(Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun `简体中文走 zh-CN`() {
        assertEquals("zh-CN", translateTargetLangOf(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-CN", translateTargetLangOf(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("zh-CN", translateTargetLangOf(Locale.forLanguageTag("zh")))
    }

    @Test
    fun `繁体中文按 script 或地区分流到 zh-TW`() {
        assertEquals("zh-TW", translateTargetLangOf(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-TW", translateTargetLangOf(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh-TW", translateTargetLangOf(Locale.forLanguageTag("zh-MO")))
        // 只带 script 不带地区,以及 script + 地区都有的形态
        assertEquals("zh-TW", translateTargetLangOf(Locale.forLanguageTag("zh-Hant")))
        assertEquals("zh-TW", translateTargetLangOf(Locale.forLanguageTag("zh-Hant-HK")))
    }

    @Test
    fun `土耳其 locale 下大小写处理不跑偏`() {
        // Turkish-I:土耳其 locale 里 "I".lowercase() 是 "ı" 而不是 "i"。
        // 语言/地区码必须按 Locale.ROOT 规则处理,否则跟随系统的土耳其用户会被算错。
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals("tr", translateTargetLangOf(Locale.forLanguageTag("tr-TR")))
        assertEquals("zh-TW", translateTargetLangOf(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-CN", translateTargetLangOf(Locale.forLanguageTag("zh-CN")))
    }

    @Test
    fun `语言码缺失时兜底英语`() {
        assertEquals("en", translateTargetLangOf(Locale.ROOT))
    }
}
