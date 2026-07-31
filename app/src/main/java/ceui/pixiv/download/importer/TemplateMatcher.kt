package ceui.pixiv.download.importer

import ceui.pixiv.download.template.TemplateNode
import ceui.pixiv.download.template.TemplateParser

/**
 * 反向模板匹配：把一条下载路径模板编译成正则，从**已经在盘上的文件名**里还原出
 * `illustId` 和页码。issue #953 的核心 —— 用户旧版下载的图新版认不出来，只能靠
 * 扫描目录 + 解析文件名重建下载记录。
 *
 * 为什么不能靠"把模板调回旧版格式再比对文件是否存在"：
 * 4.2.2 的 `FileCreator.deleteSpecialWords` 把 `-` `,` `:` `*` 全替换成 `_`，
 * 今天的 [ceui.pixiv.download.sanitize.FsSanitizer] 保留 `-` 和 `,`。标题带这些
 * 字符的作品，新版渲染出来的名字和盘上的旧文件永远不相等，没有任何模板能表达
 * 这个差异。反过来从文件名里抠 id 则不受消毒规则影响。
 *
 * 只编译模板的**最后一段**（文件名段），忽略所有目录段：
 *  - id / 页码 在 Shaft 的所有模板里都落在文件名上；
 *  - 用户扫描时选的根目录深浅不定（`Pictures` 还是 `Pictures/ShaftImages`），
 *    不匹配目录就不用关心这件事。
 *
 * 只用编号捕获组，**不用命名组** —— `Matcher.group(String)` 是 API 26 才有的，
 * 本项目 minSdk 24。
 */
class TemplateMatcher private constructor(
    /** 编译来源，日志 / 调试用。 */
    val source: String,
    /** 这条模板渲染页码时用的基准，决定 [NameMatch.page] 怎么换算回 0 基。 */
    val pageBase: PageBase,
    private val regex: Regex,
    private val idGroup: Int,
    private val pageGroup: Int,
) {

    /**
     * @return null 表示这条模板匹配不上该文件名（调用方应换下一条候选）。
     */
    fun match(filename: String): NameMatch? {
        if (filename.length > MAX_FILENAME_LENGTH) return null
        val m = regex.matchEntire(filename) ?: return null
        val id = m.groupValues.getOrNull(idGroup)?.toLongOrNull() ?: return null
        if (id <= 0L) return null
        // 页码组可能落在一个未命中的可选块里（`[?p>1:_p{page}]` 遇到单图作品），
        // 此时 groupValues 给空串 —— 视为"这张图没有页码信息"，由调用方按 0 处理。
        val printedPage = if (pageGroup > 0) {
            m.groupValues.getOrNull(pageGroup)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        } else {
            null
        }
        return NameMatch(illustId = id, printedPage = printedPage, pageBase = pageBase, source = source)
    }

    override fun toString(): String = "TemplateMatcher($source, base=$pageBase)"

    companion object {

        /** 文件名段里 `{title}` / `{author}` 允许的最大长度，兼顾 [FsSanitizer] 的 200 字节上限。 */
        private const val MAX_TEXT_RUN = 200

        /** 超过这个长度的文件名不参与匹配 —— 惰性组回溯的成本随长度增长。 */
        private const val MAX_FILENAME_LENGTH = 512

        /**
         * @param pageBase 该模板渲染 `{page}` 时用的基准。当前配置的模板传
         *   [PageBase.ONE] / [PageBase.ZERO]（看 `DownloadConfig.pageIndexFrom1`），
         *   历史模板在 [LegacyNamePatterns] 里各自写死。
         * @return null 表示这条模板没法用来还原 id（解析失败，或文件名段里根本没有
         *   `{id}`）—— 直接丢弃，不要拿去做无谓的匹配。
         */
        fun compile(templateSource: String, pageBase: PageBase): TemplateMatcher? {
            val nodes = try {
                TemplateParser(templateSource).parseAll()
            } catch (_: Exception) {
                return null
            }
            val filenameNodes = lastSegment(nodes)
            if (!containsIdVariable(filenameNodes)) return null

            val builder = PatternBuilder()
            return try {
                filenameNodes.forEach { builder.append(it) }
                if (builder.idGroup <= 0) return null
                TemplateMatcher(
                    source = templateSource,
                    pageBase = pageBase,
                    regex = Regex(builder.build()),
                    idGroup = builder.idGroup,
                    pageGroup = builder.pageGroup,
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 取模板 AST 里最后一个目录分隔符之后的部分。
         *
         * 只有 [TemplateNode.Literal] 能引入 `/` —— [TemplateContext] 渲染变量时会把
         * `/` `\` 都刷成 `_`（`scrubSeparators`），所以变量值不可能造出新的目录层级。
         * 条件块（典型如 `[?R18:R18/]`）体内带 `/` 的一律当成目录边界整块丢掉。
         */
        private fun lastSegment(nodes: List<TemplateNode>): List<TemplateNode> {
            for (i in nodes.indices.reversed()) {
                when (val node = nodes[i]) {
                    is TemplateNode.Literal -> {
                        val cut = node.text.lastIndexOfAny(charArrayOf('/', '\\'))
                        if (cut >= 0) {
                            val tail = node.text.substring(cut + 1)
                            val rest = nodes.subList(i + 1, nodes.size)
                            return if (tail.isEmpty()) rest else listOf(TemplateNode.Literal(tail)) + rest
                        }
                    }
                    is TemplateNode.Conditional -> {
                        if (bodyHasSeparator(node.body)) {
                            return nodes.subList(i + 1, nodes.size)
                        }
                    }
                    is TemplateNode.Variable -> Unit
                }
            }
            return nodes
        }

        private fun bodyHasSeparator(body: List<TemplateNode>): Boolean = body.any { node ->
            when (node) {
                is TemplateNode.Literal -> node.text.contains('/') || node.text.contains('\\')
                is TemplateNode.Conditional -> bodyHasSeparator(node.body)
                is TemplateNode.Variable -> false
            }
        }

        private fun containsIdVariable(nodes: List<TemplateNode>): Boolean = nodes.any { node ->
            when (node) {
                is TemplateNode.Variable -> node.name == "id"
                is TemplateNode.Conditional -> containsIdVariable(node.body)
                is TemplateNode.Literal -> false
            }
        }
    }

    /**
     * 边走 AST 边拼正则，同时记住 id / page 落在第几个捕获组。除 id / page 外
     * 一律用非捕获组，组号才能一路对得上。
     */
    private class PatternBuilder {
        // 不写 ^ / $ —— 匹配走 [Regex.matchEntire]，它比 $ 更严（$ 会放过结尾换行）。
        private val pattern = StringBuilder()
        private var groupCount = 0
        var idGroup = -1
            private set
        var pageGroup = -1
            private set

        fun append(node: TemplateNode) {
            when (node) {
                is TemplateNode.Literal -> pattern.append(Regex.escape(node.text))
                is TemplateNode.Variable -> appendVariable(node)
                is TemplateNode.Conditional -> {
                    pattern.append("(?:")
                    node.body.forEach { append(it) }
                    pattern.append(")?")
                }
            }
        }

        fun build(): String = pattern.toString()

        override fun toString(): String = build()

        private fun appendVariable(node: TemplateNode.Variable) {
            when (node.name) {
                "id" -> {
                    idGroup = ++groupCount
                    pattern.append("(\\d{1,12})")
                }
                "page", "page1" -> {
                    pageGroup = ++groupCount
                    pattern.append("(\\d{1,6})")
                }
                "pages", "author_id", "w", "h" -> pattern.append("(?:\\d{1,12})")
                "ext" -> pattern.append("(?:[A-Za-z0-9]{1,8})")
                "created" -> pattern.append(datePattern(node.format))
                // title / author 以及任何将来新增的文本变量：惰性 + 限长。
                // 惰性配合结尾锚点能正确切开 `{title}_{id}` 里标题自带的数字
                // （`foo_12345_67890.jpg` → title=foo_12345, id=67890）。
                else -> pattern.append("(?:[^/\\\\]{1,$MAX_TEXT_RUN}?)")
            }
        }

        /**
         * `{created:yyyyMMdd_HHmmss}` → `\d{4}\d{2}\d{2}_\d{2}\d{2}\d{2}`。
         * 每段连续字母按长度换成同样长度的数字；非字母原样转义。
         * 文本型 pattern（`MMM` → `Jan`）还原不出来，匹配不上就换下一条候选，不影响正确性。
         */
        private fun datePattern(format: String?): String {
            val fmt = format?.takeIf { it.isNotEmpty() } ?: DEFAULT_DATE_FORMAT
            val out = StringBuilder("(?:")
            var i = 0
            while (i < fmt.length) {
                val c = fmt[i]
                if (c.isLetter()) {
                    var j = i
                    while (j < fmt.length && fmt[j] == c) j++
                    out.append("\\d{").append(j - i).append('}')
                    i = j
                } else {
                    out.append(Regex.escape(c.toString()))
                    i++
                }
            }
            return out.append(')').toString()
        }
    }
}

/** `{page}` 渲染时的基准 —— 决定文件名里的数字怎么换算回 0 基页码。 */
enum class PageBase {
    /** 文件名里的 `p0` 就是第 0 页。 */
    ZERO,

    /** 文件名里的 `p1` 是第 0 页。 */
    ONE,

    /** 基准未知；调用方只能按整个作品的页码集合安全推断，仍不确定时不得建立页映射。 */
    UNKNOWN,
}

/**
 * 一次成功的文件名解析结果。
 *
 * @param printedPage 文件名里**字面**写着的页码；模板不带页码（或单图作品的
 *   `[?p>1:…]` 没渲染出来）时为 null。
 */
data class NameMatch(
    val illustId: Long,
    val printedPage: Int?,
    val pageBase: PageBase,
    /** 命中的模板来源，给"识别方式"这类调试信息用。 */
    val source: String,
)
// 想拿 0 基页码，走 PageBaseInference —— 单个 NameMatch 上不提供换算。
// `p1` 是第 0 页还是第 1 页取决于页码基准，而基准要拿同一作品所有页一起判；
// 只看一个文件名就换算，基准判错时会把第 N 页的本地图错配到第 N±1 页。

private const val DEFAULT_DATE_FORMAT = "yyyyMMdd_HHmmss"
