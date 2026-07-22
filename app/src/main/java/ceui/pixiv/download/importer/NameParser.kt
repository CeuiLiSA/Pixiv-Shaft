package ceui.pixiv.download.importer

import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.DefaultTemplates
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 文件名 → `(illustId, 页码)` 的统一入口。按可信度依次尝试：
 *
 *  1. 用户**当前**配置的插画 / 动图模板 —— 命中率最高的情况是"用户其实没换过命名规则，
 *     只是文件被 MediaStore 权限挡住了"（见 issue #953 的第二种失败模式）。
 *  2. [DefaultTemplates] 的出厂模板。
 *  3. [LegacyNamePatterns] 里 4.5.7 之前那套 cell 命名的全部组合。
 *  4. [heuristic] 兜底：直接从文件名里抠数字。
 *
 * **命中即上浮**：某条模板匹配成功后就被挪到队首。一个下载目录里的文件命名规则通常
 * 是同一套，所以第一个文件试几十条，后面几万个文件都只试一条。
 *
 * 线程安全：候选表用 [CopyOnWriteArrayList]，上浮是整表替换。扫描是单协程消费，
 * 就算并发下偶尔丢一次上浮也只是慢一点，不影响正确性。
 */
class NameParser private constructor(
    initial: List<TemplateMatcher>,
) {

    private val candidates = CopyOnWriteArrayList(initial)

    /** @return null 表示这个文件名认不出来（调用方应计入"未识别"，不要瞎猜）。 */
    fun parse(filename: String): NameMatch? {
        // CopyOnWriteArrayList 的迭代器本身就是快照，promote 期间的整表替换不会
        // 让这里 ConcurrentModificationException。
        for (matcher in candidates) {
            val hit = matcher.match(filename) ?: continue
            promote(matcher)
            return hit
        }
        return heuristic(filename)
    }

    private fun promote(winner: TemplateMatcher) {
        if (candidates.firstOrNull() === winner) return
        // remove + add(0) 不是原子的，但最坏情况只是顺序退化，不会丢候选。
        if (candidates.remove(winner)) {
            candidates.add(0, winner)
        }
    }

    companion object {

        /** 结尾的 `_p12` / `-p12` / ` p12` 页码后缀。 */
        private val PAGE_SUFFIX = Regex("""[ _\-]p(\d{1,6})$""", RegexOption.IGNORE_CASE)

        /** 5–9 位数字串 —— pixiv 作品 id 的实际取值范围。 */
        private val DIGIT_RUN = Regex("""\d{5,9}""")

        fun create(): NameParser = NameParser(buildCandidates())

        /** 单测用：只喂指定模板，不碰全局 [DownloadsRegistry] 配置。 */
        fun forTemplates(sources: List<Pair<String, PageBase>>): NameParser =
            NameParser(sources.mapNotNull { (src, base) -> TemplateMatcher.compile(src, base) })

        private fun buildCandidates(): List<TemplateMatcher> {
            val sources = mutableListOf<Pair<String, PageBase>>()

            // 1. 用户当前配置。页码基准直接读 DownloadConfig，不用猜。
            runCatching {
                val cfg = DownloadsRegistry.store.loadOrFallback()
                val base = if (cfg.pageIndexFrom1) PageBase.ONE else PageBase.ZERO
                sources += cfg.resolve(Bucket.Illust).template to base
                sources += cfg.resolve(Bucket.Ugoira).template to base
            }.onFailure {
                Timber.tag(TAG).w(it, "读取当前下载模板失败，只用内置候选")
            }

            // 2. 出厂模板。用户改过模板时这一条仍然有用 —— 盘上可能混着改模板之前下的图。
            //    出厂配置 pageIndexFrom1 默认 true，两种基准都挂上，代价只是多一条候选。
            sources += DefaultTemplates.ILLUST to PageBase.ONE
            sources += DefaultTemplates.ILLUST to PageBase.ZERO
            sources += DefaultTemplates.UGOIRA to PageBase.ZERO

            // 3. 4.5.7 之前的 cell 命名。
            sources += LegacyNamePatterns.ALL

            return sources
                .distinctBy { it.first + "|" + it.second }
                .mapNotNull { (src, base) -> TemplateMatcher.compile(src, base) }
        }

        /**
         * 所有模板都不认时的兜底：把扩展名和结尾的 `_pN` 去掉，剩下的部分里找 5–9 位数字串。
         *
         * **只有恰好剩一个候选才采用。** 多于一个说明文件名里同时有作品 id 和画师 id
         * （或标题自带长数字），分不清谁是谁 —— 宁可报"未识别"让用户知道，也不要往
         * 下载记录里写一条指向别的作品的脏数据。
         */
        internal fun heuristic(filename: String): NameMatch? {
            val stem = filename.substringBeforeLast('.', filename)
            val pageMatch = PAGE_SUFFIX.find(stem)
            val printedPage = pageMatch?.groupValues?.get(1)?.toIntOrNull()
            val body = if (pageMatch != null) stem.substring(0, pageMatch.range.first) else stem

            val ids = DIGIT_RUN.findAll(body)
                .filterNot { looksLikeDimension(body, it) }
                .map { it.value }
                .toList()
            if (ids.size != 1) return null
            val id = ids.single().toLongOrNull()?.takeIf { it > 0L } ?: return null
            return NameMatch(
                illustId = id,
                printedPage = printedPage,
                // 基准未知 —— 交给 [DownloadImporter] 按整个作品的页码集合去推断。
                pageBase = PageBase.UNKNOWN,
                source = HEURISTIC_SOURCE,
            )
        }

        /** `1920px_1080px` 这种尺寸片段里的数字不是 id。 */
        private fun looksLikeDimension(body: String, m: MatchResult): Boolean =
            body.regionMatches(m.range.last + 1, "px", 0, 2, ignoreCase = true)

        const val HEURISTIC_SOURCE = "heuristic"
        private const val TAG = "NameParser"
    }
}
