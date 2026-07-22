package ceui.pixiv.download.importer

/**
 * 4.5.7 以前那套 cell 式命名（`ceui.lisa.download.FileCreator.illustToFileName`，
 * 见 git `7ccb4fc9`）能产出的全部文件名形态，翻译成今天的模板语法后交给同一个
 * [TemplateMatcher] 编译 —— 不写第二套解析器。
 *
 * 旧实现的行为，逐条对上：
 *  - 7 个 cell 的**顺序写死**（标题 / 作品ID / P数 / 画师ID / 画师昵称 / 尺寸 / 创作时间），
 *    用户只能勾选，不能重排；勾中的部分之间用 `_` 连接。
 *  - 作品ID 默认必勾，所以这里生成的每条候选都带 `{id}` —— 不带 id 的文件名本来
 *    也还原不出 illustId。
 *  - P 数：`isHasP0()` 开 → `_p{index}`（0 基，单图也加）；关 → 仅多图 `_p{index+1}`（1 基）。
 *    两者渲染出的**形状**一样（`_pN`），差别只在基准，所以候选表里只需一条；到底是
 *    哪种由 [PageBaseInference] 按整个作品的页码集合判定。
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

    private fun buildAll(): List<Pair<String, PageBase>> {
        val out = mutableListOf<Pair<String, PageBase>>()
        // 常见组合排前面：勾选项越少越常见（默认就是 标题+ID+P数）。
        val tails = optionalTails()
        for (page in listOf("_p{page}", "")) {
            for (hasTitle in listOf(true, false)) {
                for (tail in tails) {
                    val head = if (hasTitle) "{title}_" else ""
                    // 一个模板串只挂一种基准。旧版 isHasP0 默认关 = 1 基，所以兜底用
                    // ONE；真实基准最终由 [PageBaseInference] 按整个作品的页码集合判
                    // （有 p0 就是 0 基），这里带的只是"一点证据都没有"时的默认值。
                    // 同串再挂一份 0 基是死代码 —— 正则完全一样，永远轮不到它。
                    out += ("$head{id}$page$tail.{ext}") to PageBase.ONE
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
