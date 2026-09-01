package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import ceui.pixiv.api.model.WebNovel
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.novel.reader.paginate.ImageResolver
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 合并下载（单系列章节合并 / 跨系列作者合集）共享的输出层。Single-export 走的是
 * [NovelExporter]（Novel + WebNovel + tokens），合并下载没有 tokens 这一层（章节
 * 是从 Web API 字符串里抠的），所以另开一套抽象，避免硬把 [String] 塞进 token AST。
 *
 * 文件命名 / 目录由 caller 通过 [RelativePath] 决定 —— writer 只负责把内容按格式
 * 落盘。所有 writer 都走 [ExportUtils.saveToDownloads] 写 [Bucket.NovelSeries] 桶
 * ——合集的存储位置 / 覆盖策略跟用户给「小说系列 · 合并下载」配的那套走。
 */
interface MergedNovelWriter {
    val format: ExportFormat

    /**
     * [onImageBundled] 每张插画抓回 + 编码完调一次,callsite 可以借此往 UI(比如
     * CLI 进度 dialog)报实时进度。除 EPUB writer 外其他实现都不会调。
     */
    suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
        onImageBundled: suspend (key: String, bytes: Int) -> Unit = { _, _ -> },
    ): Boolean
}

/**
 * 合并下载的内容包：封面元数据 + 章节列表。chapter 的 text 已经做了 br→\n、
 * 去 HTML 标签处理（见 [DownloadNovelTask.replaceBrWithNewLine] 等清洗路径）。
 */
data class MergedNovelContent(
    /** 显示用标题，如「《某系列》合集」「某作者 全系列合集」。 */
    val displayTitle: String,
    val author: String?,
    /** 比如 https://www.pixiv.net/novel/series/123，可空。 */
    val sourceUrl: String?,
    /** 作者写的系列简介，可空。 */
    val caption: String?,
    val chapters: List<MergedChapter>,
    /** EPUB unique-identifier 用，TXT/MD/PDF 也用作 footer。 */
    val documentId: String,
)

/**
 * 合并下载的一章。[text] 是已经做过 br→\n 清洗的正文字符串，所有 writer 都会用。
 * [webNovel] 是 EPUB writer 额外需要的——里面带着 `illusts` / `images` 两张图表，
 * 用来把 `[pixivimage:XXX]` / `[uploadedimage:XXX]` 占位符解析成真实 URL 然后嵌
 * 进 EPUB。其他 writer（TXT/MD/PDF）忽略；caller 也可以传 null（比如 cross-series
 * 模式里合成的「系列分隔头」章节本来就没插图）。
 */
data class MergedChapter(
    val title: String,
    val text: String,
    val webNovel: WebNovel? = null,
    /**
     * TXT 输出的章节头行。阅读 App 的章节识别正则对「第N章」后的行长有上限
     * （如 Legado 默认规则以 `.{0,30}$` 结尾），超长标题的头行必须截短才能被
     * 自动拆章。MD/PDF/EPUB 有结构化章节标题，不受此限制，始终用完整 [title]。
     */
    val txtHeaderLine: String = title,
    /**
     * 头行被截短时，TXT writer 把完整标题作为这一行写在头行下面（#903 报告者
     * 建议的方案）。注意这里**不带**「第N章」前缀——带前缀的话，标题恰好 29 字
     * 时这一行（章后 30 字符）也落在阅读 App 的正则行长限制内，同一章会被识别
     * 成两个章节。短标题 / 合成章节为 null，TXT 不写副标题行。
     */
    val txtSubtitleLine: String? = null,
) {
    companion object {
        /**
         * 「第N章 」之后标题部分的最大字符数。Legado 等阅读 App 的默认章节正则
         * 要求「章」后（含空格）不超过 30 字符：1 空格 + 27 字 + 1 省略号 = 29，
         * 卡在限制内。
         */
        private const val READER_LINE_TITLE_MAX = 28

        private val WHITESPACE = Regex("\\s+")

        /**
         * 正文章节的统一构造入口（#903），三条合并下载路径（单系列 / 跨系列
         * PerSeries / 跨系列 AllMergedOne）都走这里，保证格式不分叉：
         *  - 标题统一「第N章 标题」格式——「章」是各阅读 App 章节识别正则的最大
         *    公约数（「篇」Moon+ Reader 不认，「•」紧跟数字会破坏匹配）
         *  - [title] 保留完整标题不截断；仅 TXT 头行超长时另给截短版
         *  - 标题里的换行 / 连续空白折叠成单个空格，保证章节头行不会被拆成多行
         */
        fun numbered(
            position: Int,
            rawTitle: String,
            text: String,
            webNovel: WebNovel? = null,
        ): MergedChapter {
            val cleanTitle = WHITESPACE.replace(rawTitle, " ").trim()
            val prefix = "第${position}章 "
            val full = prefix + cleanTitle
            val truncated = cleanTitle.length > READER_LINE_TITLE_MAX
            val headerLine = if (truncated) {
                prefix + takeSafely(cleanTitle, READER_LINE_TITLE_MAX - 1) + "…"
            } else {
                full
            }
            return MergedChapter(
                title = full,
                text = text,
                webNovel = webNovel,
                txtHeaderLine = headerLine,
                txtSubtitleLine = if (truncated) cleanTitle else null,
            )
        }

        /** [String.take] 的不拆 surrogate pair 版：截断点落在 emoji 等非 BMP 字符中间时少取一个 char。 */
        private fun takeSafely(s: String, n: Int): String {
            val cut = s.take(n)
            return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
        }
    }
}

object MergedNovelWriters {
    private val writers: Map<ExportFormat, MergedNovelWriter> = mapOf(
        ExportFormat.Txt to MergedTxtWriter,
        ExportFormat.Markdown to MergedMarkdownWriter,
        ExportFormat.Pdf to MergedPdfWriter,
        ExportFormat.Epub to MergedEpubWriter,
    )

    fun forFormat(format: ExportFormat): MergedNovelWriter = writers.getValue(format)
}

// ────────────────────────────────────────────────────────────────────
// TXT
// ────────────────────────────────────────────────────────────────────

private object MergedTxtWriter : MergedNovelWriter {
    override val format = ExportFormat.Txt

    override suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
        onImageBundled: suspend (key: String, bytes: Int) -> Unit,
    ): Boolean {
        val text = buildString {
            append("《").append(content.displayTitle).append("》\n")
            content.author?.takeIf { it.isNotBlank() }?.let { append("作者: ").append(it).append('\n') }
            content.sourceUrl?.takeIf { it.isNotBlank() }?.let { append("来源: ").append(it).append('\n') }
            content.caption?.takeIf { it.isNotBlank() }?.let {
                append('\n').append("简介:\n").append(it.trim()).append('\n')
            }
            append('\n').append("----------------------\n\n\n")
            content.chapters.forEach { ch ->
                // 章节头独占一行、不加装饰符号 —— 阅读 App 才能用「^第N章」正则
                // 自动拆章(#903)。装饰版 <===== 标题 =====> 没有软件认。
                // 头行可能是截短版(见 MergedChapter.txtHeaderLine);截短过的把
                // 完整标题(不带「第N章」前缀,见 txtSubtitleLine)补在头行下一行。
                append("\n\n").append(ch.txtHeaderLine).append('\n')
                ch.txtSubtitleLine?.let { append(it).append('\n') }
                append('\n')
                append(ch.text)
                append("\n\n")
            }
        }
        val uri = ExportUtils.saveToDownloads(context, destination, format.mimeType, Bucket.NovelSeries) { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        return uri != null
    }
}

// ────────────────────────────────────────────────────────────────────
// Markdown
// ────────────────────────────────────────────────────────────────────

private object MergedMarkdownWriter : MergedNovelWriter {
    override val format = ExportFormat.Markdown

    override suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
        onImageBundled: suspend (key: String, bytes: Int) -> Unit,
    ): Boolean {
        val text = buildString {
            append("# ").append(content.displayTitle).append("\n\n")
            content.author?.takeIf { it.isNotBlank() }?.let { append("**作者**: ").append(it).append("  \n") }
            content.sourceUrl?.takeIf { it.isNotBlank() }?.let { append("**来源**: <").append(it).append(">  \n") }
            content.caption?.takeIf { it.isNotBlank() }?.let {
                append('\n').append("> ").append(it.trim().replace("\n", "\n> ")).append('\n')
            }
            append("\n---\n")
            content.chapters.forEach { ch ->
                append("\n## ").append(ch.title).append("\n\n")
                ch.text.split('\n').forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) append('\n') else append(trimmed).append("\n\n")
                }
            }
        }
        val uri = ExportUtils.saveToDownloads(context, destination, format.mimeType, Bucket.NovelSeries) { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        return uri != null
    }
}

// ────────────────────────────────────────────────────────────────────
// PDF
// ────────────────────────────────────────────────────────────────────
//
// Layout pen-pictures the single-novel [PdfExporter]: A4 @72dpi, serif body,
// chapter heads on a fresh page, paragraph-flow with line-level page splits.
// Images are skipped — merge sources have hundreds of chapters; embedding
// every illustration would blow file size up. Picking EPUB stays the path
// for "I want pictures with my text."

private object MergedPdfWriter : MergedNovelWriter {
    override val format = ExportFormat.Pdf

    private const val PAGE_WIDTH_PT = 595
    private const val PAGE_HEIGHT_PT = 842
    private const val MARGIN_PT = 48f
    private val contentWidthPt: Float = PAGE_WIDTH_PT - MARGIN_PT * 2

    override suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
        onImageBundled: suspend (key: String, bytes: Int) -> Unit,
    ): Boolean {
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = 12f
            color = 0xFF222222.toInt()
        }
        // Chapter title 走 StaticLayout + Layout.Alignment.ALIGN_CENTER；不再
        // 设 Paint.Align.CENTER —— 两套对齐机制混用在部分 Android 版本下会让
        // 字符 x 偏移半字宽。与单篇 PdfExporter.chapterPaint 一致。
        val chapterPaint = TextPaint(bodyPaint).apply {
            textSize = 18f
            isFakeBoldText = true
        }
        val titlePaint = TextPaint(bodyPaint).apply {
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val metaPaint = TextPaint(bodyPaint).apply {
            textSize = 10f
            color = 0xFF777777.toInt()
            textAlign = Paint.Align.CENTER
        }
        val captionPaint = TextPaint(bodyPaint).apply {
            textSize = 11f
            color = 0xFF555555.toInt()
        }

        val document = PdfDocument()
        val state = PageState()
        state.start(document)

        // Cover block (drawn once at the top of page 1).
        state.currentPage?.canvas?.let { canvas ->
            val cx = PAGE_WIDTH_PT / 2f
            canvas.drawText(content.displayTitle, cx, state.y + 24f, titlePaint)
            state.y += 60f
            content.author?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("作者: $it", cx, state.y, metaPaint)
                state.y += 16f
            }
            content.sourceUrl?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(it, cx, state.y, metaPaint)
                state.y += 16f
            }
            canvas.drawText(
                "${content.chapters.size} 章 · Pixiv-Shaft",
                cx, state.y, metaPaint,
            )
            state.y += 48f
        }
        content.caption?.takeIf { it.isNotBlank() }?.let { cap ->
            state.flowText(document, cap.trim(), captionPaint, lineSpacingMultiplier = 1.4f)
            state.y += 16f
        }

        content.chapters.forEach { ch ->
            state.startNewPage(document)
            val titleLayout = TextMeasurer.buildStaticLayout(
                text = ch.title,
                paint = chapterPaint,
                width = contentWidthPt.toInt(),
                lineSpacingMultiplier = 1.2f,
                lineSpacingExtra = 0f,
                alignment = Layout.Alignment.ALIGN_CENTER,
            )
            state.drawLayout(titleLayout)
            state.y += 24f
            state.flowText(document, ch.text, bodyPaint, lineSpacingMultiplier = 1.55f)
        }
        state.finish(document)

        val uri = ExportUtils.saveToDownloads(context, destination, format.mimeType, Bucket.NovelSeries) { out ->
            document.writeTo(out)
        }
        document.close()
        return uri != null
    }

    /** Drawing cursor shared between the cover block, chapter heads, and paragraph flow. */
    private class PageState {
        var currentPage: PdfDocument.Page? = null
        var pageNumber = 0
        var y: Float = 0f

        fun start(document: PdfDocument) {
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, pageNumber).create()
            currentPage = document.startPage(info)
            y = MARGIN_PT
        }

        fun startNewPage(document: PdfDocument) {
            finish(document)
            start(document)
        }

        fun finish(document: PdfDocument) {
            currentPage?.let { document.finishPage(it) }
            currentPage = null
        }

        fun drawLayout(layout: Layout) {
            val canvas = currentPage?.canvas ?: return
            val save = canvas.save()
            canvas.translate(MARGIN_PT, y)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            y += layout.height
        }

        fun flowText(
            document: PdfDocument,
            text: String,
            paint: TextPaint,
            lineSpacingMultiplier: Float,
        ) {
            // Paragraph-by-paragraph so a chapter break aligns with a real
            // paragraph boundary rather than a mid-sentence newline.
            val paragraphs = text.split('\n').map { it.trim() }
            for (p in paragraphs) {
                if (p.isEmpty()) {
                    y += paint.textSize
                    continue
                }
                val layout = TextMeasurer.buildStaticLayout(
                    text = p,
                    paint = paint,
                    width = contentWidthPt.toInt(),
                    lineSpacingMultiplier = lineSpacingMultiplier,
                    lineSpacingExtra = 0f,
                )
                drawLayoutWithPageSplit(document, layout)
                y += paint.textSize * 0.6f
            }
        }

        private fun drawLayoutWithPageSplit(document: PdfDocument, layout: StaticLayout) {
            var lineCursor = 0
            while (lineCursor < layout.lineCount) {
                val remaining = (PAGE_HEIGHT_PT - MARGIN_PT) - y
                val startTop = layout.getLineTop(lineCursor)
                var fit = 0
                var used = 0f
                for (i in lineCursor until layout.lineCount) {
                    val h = (layout.getLineBottom(i) - startTop).toFloat()
                    if (h > remaining && fit > 0) break
                    fit = i - lineCursor + 1
                    used = h
                }
                if (fit == 0) {
                    startNewPage(document)
                    continue
                }
                val canvas = currentPage?.canvas
                if (canvas != null) {
                    val save = canvas.save()
                    canvas.translate(MARGIN_PT, y - layout.getLineTop(lineCursor).toFloat())
                    canvas.clipRect(
                        0f,
                        layout.getLineTop(lineCursor).toFloat(),
                        layout.width.toFloat(),
                        layout.getLineBottom(lineCursor + fit - 1).toFloat(),
                    )
                    layout.draw(canvas)
                    canvas.restoreToCount(save)
                }
                y += used
                lineCursor += fit
                if (lineCursor < layout.lineCount) startNewPage(document)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// EPUB
// ────────────────────────────────────────────────────────────────────
//
// Mirrors single-novel [EpubExporter] structure: mimetype (STORED first),
// META-INF/container.xml, OEBPS/{content.opf, toc.ncx, content.xhtml,
// images/<id>.jpg}. One combined XHTML with h2 anchors per chapter.
//
// 与 PDF/TXT/MD 不同——EPUB 是「我要图」那一档（见上面 PDF 段的注释）。每章
// 都把 text 跑一遍 [ContentParser.tokenize]，遇到 `[pixivimage:XXX]` /
// `[uploadedimage:XXX]` 就用本章 [MergedChapter.webNovel] 里的 illusts / images
// 图表去解析 URL，Glide 同步抓回来 → JPEG → 塞 OEBPS/images/。整本 EPUB 内全
// 局按 image key 去重（同一张插画在多章引用只下载一次）。网络失败 / 没图表 /
// 解析不出 URL：单张降级成 `<em>[图片 ...]</em>` 文本占位，整本继续导出。

private object MergedEpubWriter : MergedNovelWriter {
    override val format = ExportFormat.Epub

    private data class BundledImage(val fileName: String, val bytes: ByteArray)

    private class TokenizedChapter(
        val title: String,
        val tokens: List<ContentToken>,
        val resolver: (ContentToken) -> String?,
    )

    override suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
        onImageBundled: suspend (key: String, bytes: Int) -> Unit,
    ): Boolean {
        val title = content.displayTitle.ifEmpty { "novel_merge" }
        val novelId = content.documentId.ifEmpty { System.currentTimeMillis().toString() }
        val author = content.author.orEmpty()

        val tokenized = content.chapters.map { ch ->
            val resolver: (ContentToken) -> String? =
                ch.webNovel?.let { ImageResolver.of(it) } ?: { null }
            TokenizedChapter(
                title = ch.title,
                tokens = ContentParser.tokenize(ch.text),
                resolver = resolver,
            )
        }

        // 跨章节去重：相同 image key 的插画只下载一次。第一次解析到 URL 就抓。
        val images = linkedMapOf<String, BundledImage>()
        for (ch in tokenized) {
            for (token in ch.tokens) {
                val key = imageKey(token) ?: continue
                if (images.containsKey(key)) continue
                val url = ch.resolver(token) ?: continue
                val bitmap = ExportUtils.loadBitmap(context, url) ?: continue
                val jpeg = ExportUtils.bitmapToJpeg(bitmap)
                images[key] = BundledImage("$key.jpg", jpeg)
                onImageBundled(key, jpeg.size)
            }
        }

        val chapterHtml = buildChapterXhtml(
            title, author, content.sourceUrl, content.caption, tokenized, images,
        )
        val opf = buildContentOpf(novelId, title, author, images.keys)
        val ncx = buildTocNcx(novelId, title, tokenized)

        val uri = ExportUtils.saveToDownloads(context, destination, format.mimeType, Bucket.NovelSeries) { out ->
            ZipOutputStream(out).use { zip ->
                storeEntry(zip, "mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
                deflateEntry(zip, "META-INF/container.xml", CONTAINER_XML.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/content.opf", opf.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/toc.ncx", ncx.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/content.xhtml", chapterHtml.toByteArray(Charsets.UTF_8))
                for ((_, img) in images) {
                    deflateEntry(zip, "OEBPS/images/${img.fileName}", img.bytes)
                }
            }
        }
        return uri != null
    }

    private fun imageKey(token: ContentToken): String? = when (token) {
        is ContentToken.UploadedImage -> "uploaded_${token.imageId}"
        is ContentToken.PixivImage -> "pixiv_${token.illustId}_${token.pageIndex}"
        else -> null
    }

    private fun buildChapterXhtml(
        title: String,
        author: String,
        sourceUrl: String?,
        caption: String?,
        chapters: List<TokenizedChapter>,
        images: Map<String, BundledImage>,
    ): String {
        val body = buildString {
            append("<h1>").append(escape(title)).append("</h1>\n")
            if (author.isNotEmpty()) {
                append("<p class=\"meta\">作者: ").append(escape(author)).append("</p>\n")
            }
            if (!sourceUrl.isNullOrBlank()) {
                append("<p class=\"meta\">").append(escape(sourceUrl)).append("</p>\n")
            }
            if (!caption.isNullOrBlank()) {
                append("<div class=\"caption\"><p>")
                append(escape(caption.trim()).replace("\n", "</p><p>"))
                append("</p></div>\n")
            }
            append("<hr/>\n")
            chapters.forEachIndexed { index, ch ->
                val anchor = "ch${index + 1}"
                append("<h2 id=\"").append(anchor).append("\">")
                append(escape(ch.title)).append("</h2>\n")
                for (token in ch.tokens) {
                    when (token) {
                        is ContentToken.Paragraph -> {
                            append("<p>").append(escape(token.text)).append("</p>\n")
                        }
                        is ContentToken.BlankLine -> append("<br/>\n")
                        is ContentToken.PageBreak -> append("<hr/>\n")
                        is ContentToken.Chapter -> {
                            // 章内嵌套的 [chapter:] 标题降一级，避免和合集 h2 撞
                            append("<h3>").append(escape(token.title)).append("</h3>\n")
                        }
                        is ContentToken.PixivImage -> {
                            val key = imageKey(token)
                            if (key != null && images.containsKey(key)) {
                                append("<p class=\"image\"><img src=\"images/").append(key)
                                    .append(".jpg\" alt=\"pixiv ").append(token.illustId)
                                    .append("\"/></p>\n")
                            } else {
                                val url = ch.resolver(token).orEmpty()
                                append("<p class=\"image\"><em>[图片 pixiv ")
                                    .append(token.illustId).append(": ")
                                    .append(escape(url)).append("]</em></p>\n")
                            }
                        }
                        is ContentToken.UploadedImage -> {
                            val key = imageKey(token)
                            if (key != null && images.containsKey(key)) {
                                append("<p class=\"image\"><img src=\"images/").append(key)
                                    .append(".jpg\" alt=\"uploaded ").append(token.imageId)
                                    .append("\"/></p>\n")
                            } else {
                                val url = ch.resolver(token).orEmpty()
                                append("<p class=\"image\"><em>[图片 ")
                                    .append(token.imageId).append(": ")
                                    .append(escape(url)).append("]</em></p>\n")
                            }
                        }
                        is ContentToken.Jump -> {
                            append("<p class=\"jump\"><em>[跳转→第 ")
                                .append(token.target).append(" 段]</em></p>\n")
                        }
                    }
                }
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>${escape(title)}</title>
  <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=utf-8"/>
  <style type="text/css">
    body { font-family: serif; line-height: 1.7; padding: 1em; }
    h1 { text-align: center; }
    h2 { border-bottom: 1px solid #ccc; padding-bottom: 0.25em; margin-top: 2em; }
    h3 { margin-top: 1.5em; }
    p { text-indent: 2em; margin: 0.5em 0; }
    p.meta { text-indent: 0; color: #666; font-size: 0.9em; text-align: center; }
    .caption { border-left: 3px solid #ccc; padding: 0.5em 1em; margin: 1em 0; background: #f9f9f9; }
    .caption p { text-indent: 0; color: #555; font-size: 0.95em; }
    p.image { text-indent: 0; text-align: center; margin: 1em 0; }
    p.image img { max-width: 100%; }
    hr { border: 0; border-top: 1px dashed #aaa; margin: 2em 25%; }
  </style>
</head>
<body>
$body
</body>
</html>
"""
    }

    private fun buildContentOpf(
        novelId: String,
        title: String,
        author: String,
        imageKeys: Set<String>,
    ): String {
        val imageManifest = imageKeys.joinToString("\n    ") { key ->
            """<item id="img_$key" href="images/$key.jpg" media-type="image/jpeg"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${escape(title)}</dc:title>
    <dc:creator opf:role="aut">${escape(author.ifEmpty { "Pixiv" })}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId" opf:scheme="pixiv">${escape(novelId)}</dc:identifier>
    <dc:publisher>Pixiv-Shaft</dc:publisher>
  </metadata>
  <manifest>
    <item id="content" href="content.xhtml" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    ${if (imageManifest.isNotEmpty()) imageManifest else ""}
  </manifest>
  <spine toc="ncx">
    <itemref idref="content"/>
  </spine>
</package>
"""
    }

    private fun buildTocNcx(novelId: String, title: String, chapters: List<TokenizedChapter>): String {
        val navPoints = if (chapters.isEmpty()) {
            """<navPoint id="p1" playOrder="1">
              <navLabel><text>${escape(title)}</text></navLabel>
              <content src="content.xhtml"/>
            </navPoint>"""
        } else {
            chapters.mapIndexed { index, ch ->
                """<navPoint id="p${index + 1}" playOrder="${index + 1}">
              <navLabel><text>${escape(ch.title)}</text></navLabel>
              <content src="content.xhtml#ch${index + 1}"/>
            </navPoint>"""
            }.joinToString("\n    ")
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="${escape(novelId)}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${escape(title)}</text></docTitle>
  <navMap>
    $navPoints
  </navMap>
</ncx>
"""
    }

    private fun storeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun deflateEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""
}
