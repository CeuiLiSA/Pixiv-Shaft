package ceui.pixiv.download.importer

import ceui.lisa.download.FileCreator
import ceui.lisa.model.CustomFileNameCell

/**
 * 4.5.7 以前那套 cell 式命名（`ceui.lisa.download.FileCreator.illustToFileName`，
 * 见 git `7ccb4fc9`）能产出的全部文件名形态，翻译成今天的模板语法后交给同一个
 * [TemplateMatcher] 编译 —— 不写第二套解析器。
 *
 * 旧实现的行为，逐条对上：
 *  - 默认顺序是标题 / 作品ID / P数 / 画师ID / 画师昵称 / 尺寸 / 创作时间，但旧设置页
 *    支持拖拽重排。默认组合由 [ALL] 兜底；仍保存在 Settings.fileNameJson 里的真实顺序
 *    由 [fromCells] 精确还原，避免作品 ID 和画师 ID 对调后误判。
 *  - 作品ID 默认必勾，所以这里生成的每条候选都带 `{id}` —— 不带 id 的文件名本来
 *    也还原不出 illustId。
 *  - P 数：`isHasP0()` 开 → `_p{index}`（0 基，单图也加）；关 → 仅多图 `_p{index+1}`（1 基）。
 *    两者渲染出的**形状**一样（`_pN`），没有 `p0` 时单靠文件名无法安全区分，所以通用
 *    候选必须标 [PageBase.UNKNOWN]；只有仍保留旧设置的精确候选才携带当时的设置值。
 *  - 尺寸渲染成 `1920px*1080px`，随后 `deleteSpecialWords` 把 `*` 换成 `_`
 *    → 盘上实际是 `1920px_1080px`。
 *  - 创作时间是 `Common.getLocalYYYYMMDDHHMMSSFileString` = `yyyyMMdd_HHmmss`，
 *    和 `{created}` 的默认格式一致。
 *
 * 组合数是 2（有无标题）× 2（有无页码）× 2^4（四个可选尾巴）= 64 条。看着多，但
 * [NameParser] 命中一次之后会把胜出的那条顶到队首，稳态下每个文件只试一条。
 */
object LegacyNamePatterns {

    /** 尺寸 cell：`*` 已被旧消毒规则换成 `_`。 */
    private const val SIZE = "{w}px_{h}px"

    /** 创作时间 cell。 */
    private const val CREATED = "{created}"

    /**
     * 按旧实现的 cell 顺序拼出所有候选。位置固定：
     * `[标题_]{id}[_p页码][_画师ID][_画师昵称][_尺寸][_创作时间].{ext}`
     */
    val ALL: List<Pair<String, PageBase>> by lazy { buildAll() }

    /**
     * 按旧设置里保存的真实 cell 顺序生成精确模板。
     *
     * 旧版页码 cell 在 1 基单图时完全不输出，因此同时生成“有页码”和“无页码”两种形态；
     * 0 基设置连单图也输出 p0，只需要有页码形态。
     */
    fun fromCells(
        cells: List<CustomFileNameCell>,
        pageBase: PageBase,
    ): List<Pair<String, PageBase>> {
        val enabled = cells.filter { it.isChecked }
        if (enabled.none { it.code == FileCreator.ILLUST_ID }) return emptyList()

        fun part(code: Int): String? = when (code) {
            FileCreator.ILLUST_TITLE -> "{title}"
            FileCreator.ILLUST_ID -> "{id}"
            FileCreator.P_SIZE -> "p{page}"
            FileCreator.USER_ID -> "{author_id}"
            FileCreator.USER_NAME -> "{author}"
            FileCreator.ILLUST_SIZE -> SIZE
            FileCreator.CREATE_TIME -> CREATED
            else -> null
        }

        fun build(includePage: Boolean): String {
            val parts = enabled.mapNotNull { cell ->
                if (!includePage && cell.code == FileCreator.P_SIZE) null else part(cell.code)
            }
            return parts.joinToString("_") + ".{ext}"
        }

        val out = mutableListOf(build(includePage = true) to pageBase)
        if (pageBase != PageBase.ZERO && enabled.any { it.code == FileCreator.P_SIZE }) {
            out += build(includePage = false) to pageBase
        }
        return out.distinctBy { it.first }
    }

    private fun buildAll(): List<Pair<String, PageBase>> {
        val out = mutableListOf<Pair<String, PageBase>>()
        // 常见组合排前面：勾选项越少越常见（默认就是 标题+ID+P数）。
        val tails = optionalTails()
        for (page in listOf("_p{page}", "")) {
            for (hasTitle in listOf(true, false)) {
                for (tail in tails) {
                    val head = if (hasTitle) "{title}_" else ""
                    // 同一形状既可能来自 0 基也可能来自 1 基。没有 p0 时不可猜，
                    // 否则会把本地第 N 页错配成第 N±1 页。
                    out += ("$head{id}$page$tail.{ext}") to PageBase.UNKNOWN
                }
            }
        }
        return out.distinctBy { it.first }
    }

    /** `{author_id}` / `{author}` / 尺寸 / 创作时间 四个可选尾巴的全部组合，短的在前。 */
    private fun optionalTails(): List<String> {
        val cells = listOf("{author_id}", "{author}", SIZE, CREATED)
        val combos = mutableListOf<String>()
        for (mask in 0 until (1 shl cells.size)) {
            val parts = cells.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
            combos += if (parts.isEmpty()) "" else parts.joinToString("_", prefix = "_")
        }
        return combos.sortedBy { it.length }
    }
}
