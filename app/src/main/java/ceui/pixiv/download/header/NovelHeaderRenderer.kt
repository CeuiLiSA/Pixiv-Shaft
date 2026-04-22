package ceui.pixiv.download.header

import ceui.loxia.Novel
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.task.DownloadNovelTask

/**
 * Renders the metadata block prepended to every downloaded novel TXT file.
 *
 * The output is deterministic for a given (novel, preset) pair. Fields are
 * emitted in the order they appear in [HeaderPreset.fields]; series-only
 * fields are silently dropped when the novel is not part of a series.
 *
 * The rendered block ends with a separator line so the human eye can
 * clearly see where metadata ends and the novel text begins.
 */
object NovelHeaderRenderer {

    private const val SEPARATOR = "—————————————"

    fun render(
        novel: Novel,
        preset: HeaderPreset,
        isSeriesChapter: Boolean,
        seriesIndex: Int? = null,
        seriesTotal: Int? = null,
    ): String {
        val sb = StringBuilder()
        for (field in preset.fields) {
            if (!isSeriesChapter && HeaderField.isSeriesOnly(field)) continue
            val line = renderField(field, novel, seriesIndex, seriesTotal) ?: continue
            sb.append(line)
            sb.append('\n')
            sb.append('\n')
        }
        sb.append(SEPARATOR)
        sb.append('\n')
        return sb.toString()
    }

    private fun renderField(
        field: HeaderField,
        novel: Novel,
        seriesIndex: Int?,
        seriesTotal: Int?,
    ): String? {
        return when (field) {
            HeaderField.Title       -> "标题：${novel.title.orEmpty()}"
            HeaderField.Author      -> "作者：${novel.user?.name.orEmpty()}"
            HeaderField.AuthorId    -> "作者ID：${novel.user?.id ?: ""}"
            HeaderField.NovelId     -> "作品ID：${novel.id}"
            HeaderField.NovelLink   -> "作品链接：$NOVEL_URL_HEAD${novel.id}"
            HeaderField.Caption     -> {
                val cleaned = DownloadNovelTask.replaceBrWithNewLine(novel.caption)
                if (cleaned.isBlank()) null else "简介：\n$cleaned"
            }
            HeaderField.PublishTime -> "发布时间：${novel.create_date.orEmpty()}"
            HeaderField.TextLength  -> "字数：${novel.text_length ?: 0}"
            HeaderField.Tags        -> {
                val tags = novel.tags.orEmpty()
                    .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                if (tags.isEmpty()) null else "标签：${tags.joinToString(", ")}"
            }
            HeaderField.SeriesTitle -> {
                val title = novel.series?.title?.takeIf { it.isNotBlank() } ?: return null
                "系列：$title"
            }
            HeaderField.SeriesIndex -> {
                val idx = seriesIndex ?: return null
                val total = seriesTotal?.toString() ?: "?"
                "序号：第 ${idx + 1} / $total 篇"
            }
        }
    }
}
