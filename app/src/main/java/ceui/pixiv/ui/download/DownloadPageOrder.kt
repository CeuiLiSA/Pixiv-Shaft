package ceui.pixiv.ui.download

import ceui.lisa.database.DownloadEntity

/**
 * 下载记录里同一作品各页的展示顺序（issue #1074）。
 *
 * 1. 先按 [DownloadEntity.getPage]：新下载由 Manager 写入、老行由 DownloadPageBackfill
 *    从文件名回填，是真正的页码；`-1`（未回填）/`-2`（解析不出）的行排到已知页之后。
 * 2. 页码相同或未知时按文件名**自然序**：`p1, p2, …, p10`，而不是 String 字典序的
 *    `p1, p10, p2`。
 */
internal val DownloadPageOrder: Comparator<DownloadEntity> =
    compareBy<DownloadEntity> { it.page.takeIf { p -> p >= 0 } ?: Int.MAX_VALUE }
        .thenComparator { a, b -> NaturalOrder.compare(a.fileName.orEmpty(), b.fileName.orEmpty()) }

/**
 * 数字段按数值、其余按字符比较的自然序：`a2 < a10`、`xxx2xxx < xxx10xxx`。
 * 只认 ASCII 数字；数值相等（如 `p01` 与 `p1`）继续比后面的字符，最后按原串兜底保证全序。
 */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val ia = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val jb = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                val na = a.substring(ia, i).trimStart('0')
                val nb = b.substring(jb, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = a[i].compareTo(b[j])
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
