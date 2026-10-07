package ceui.pixiv.ui.translate

import kotlin.math.roundToInt

/**
 * 「原样度」:衡量译文是否只是把原文原样吐了出来(#975 自定义 AI 翻译的指令遵循校验)。
 *
 * 算法:把原文去掉链接,再去掉空白 / 标点 / 符号 / emoji 等非字母数字字符,拆成单字,逐字到译文里查
 * 是否出现;命中数 ÷ 单字总数 = 原样度,天然落在 [0, 1](最高为 1)。
 *
 * 单个字命中不算数 —— 中日共用汉字会让「正经翻译」也带一点命中,所以只有 [isLikelyVerbatim]
 * 的阈值判定才算「模型可能原样输出了原文」,且原文归一化后短于 [MIN_NORMALIZED_LENGTH] 时
 * 不判定,避免标签这类超短文本误报。
 *
 * 纯 Kotlin、不依赖 Android,方便 JVM 单测直接覆盖。
 */
internal object VerbatimRatio {

    /** 原样度达到这个值即判为「疑似原样输出」。 */
    const val THRESHOLD = 0.8

    /** 归一化原文短于此长度不判定,避免两三个字的标签 / 短气泡误报。 */
    const val MIN_NORMALIZED_LENGTH = 4

    /** 链接(http/https/ftp 或 www. 开头)。链接常被译文原样保留,不算「原文内容」,归一化时整段剔除。 */
    private val LINK_REGEX = Regex(
        "(?i)\\b(?:https?|ftp)://[^\\s\\u3000-\\u303F\\u3040-\\u30FF\\u4E00-\\u9FFF\\uFF00-\\uFFEF]+" +
            "|\\bwww\\.[^\\s\\u3000-\\u303F\\u3040-\\u30FF\\u4E00-\\u9FFF\\uFF00-\\uFFEF]+"
    )

    /**
     * 去掉链接后,只保留字母与数字(含中日文、假名、拉丁、数字),去掉空白 / 标点 / 符号 / emoji。
     * 链接单独剔除是因为它在译文里常被原样保留,留着会抬高原样度、造成误判。
     */
    fun normalize(text: String): String {
        val withoutLinks = LINK_REGEX.replace(text, " ")
        return buildString(withoutLinks.length) {
            for (c in withoutLinks) if (c.isLetterOrDigit()) append(c)
        }
    }

    /** 逐字命中率,范围 [0, 1];原文归一化后为空返回 0。 */
    fun ratio(original: String, translated: String): Double {
        val source = normalize(original)
        if (source.isEmpty()) return 0.0
        val target = normalize(translated)
        if (target.isEmpty()) return 0.0
        var hit = 0
        for (c in source) if (target.contains(c)) hit++
        return hit.toDouble() / source.length
    }

    /** 是否疑似「模型原样输出了原文」:归一化原文够长且原样度 ≥ [THRESHOLD]。 */
    fun isLikelyVerbatim(original: String, translated: String): Boolean {
        if (normalize(original).length < MIN_NORMALIZED_LENGTH) return false
        return ratio(original, translated) >= THRESHOLD
    }

    /** 原样度百分比(0–100),用于提示文案。 */
    fun percent(original: String, translated: String): Int =
        (ratio(original, translated) * 100).roundToInt()
}

/**
 * 「原样度」只对会「原样回显」的 AI 引擎生效:自定义 AI([AiTranslator])与云翻译
 * ([CloudTranslator],服务端含 GPT 上游);Google 免费端点不检测 —— 它几乎不会原样回显,
 * 检测只会制造噪音。
 *
 * 云翻译在服务端关停时会用 Google 重做本批([CloudTranslator.servedByGoogleFallback]),
 * 那一批产物不是 AI 译文,同样跳过判定。
 */
internal fun Translator.isAiBacked(): Boolean =
    this === AiTranslator || (this === CloudTranslator && !CloudTranslator.servedByGoogleFallback)