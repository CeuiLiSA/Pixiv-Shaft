package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.TextPaint
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer

/**
 * Renders the novel to a multi-page A4 PDF using [android.graphics.pdf.PdfDocument].
 *
 * Strategy:
 * - Use [TextMeasurer] to lay out each paragraph at a fixed A4 content width.
 * - Flow lines onto pages, starting a new page when we run out of vertical space.
 * - Chapter titles get a larger paint and always start on a fresh page.
 * - Images are skipped for now — they'd require sync Glide loads per token and
 *   PDF image embedding which blows up the file size quickly. Users who want
 *   images in exports should pick EPUB.
 */
class PdfExporter : NovelExporter {
    override val format: ExportFormat = ExportFormat.Pdf

    // A4 at 72 dpi
    private val pageWidthPt = 595
    private val pageHeightPt = 842
    private val marginPt = 48f
    private val contentWidthPt = pageWidthPt - marginPt * 2
    private val contentHeightPt = pageHeightPt - marginPt * 2

    override suspend fun export(
        context: Context,
        novel: Novel?,
        webNovel: WebNovel,
        tokens: List<ContentToken>,
        fileName: String,
    ): ExportResult {
        val title = novel?.title ?: webNovel.title.orEmpty()
        val author = novel?.user?.name.orEmpty()
        val novelId = novel?.id ?: webNovel.id.orEmpty()

        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = 12f
            color = 0xFF222222.toInt()
        }
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

        val document = PdfDocument()
        val state = PageState()

        fun startNewPage() {
            state.finish(document)
            state.start(document, pageWidthPt, pageHeightPt, marginPt)
        }

        // Cover / title block
        state.start(document, pageWidthPt, pageHeightPt, marginPt)
        state.currentPage?.canvas?.let { canvas ->
            val cx = pageWidthPt / 2f
            canvas.drawText(title, cx, state.y + 24f, titlePaint)
            state.y += 60f
            if (author.isNotEmpty()) {
                canvas.drawText("作者: $author", cx, state.y, metaPaint)
                state.y += 16f
            }
            canvas.drawText("Pixiv · id=$novelId", cx, state.y, metaPaint)
            state.y += 48f
        }

        for (token in tokens) {
            when (token) {
                is ContentToken.PageBreak -> startNewPage()
                is ContentToken.BlankLine -> state.y += bodyPaint.textSize
                is ContentToken.Chapter -> {
                    startNewPage()
                    val layout = TextMeasurer.buildLayout(
                        text = token.title,
                        paint = chapterPaint,
                        width = contentWidthPt.toInt(),
                        lineSpacingMultiplier = 1.2f,
                        lineSpacingExtra = 0f,
                        alignment = Layout.Alignment.ALIGN_CENTER,
                    )
                    state.drawLayout(layout, marginPt)
                    state.y += 24f
                }
                is ContentToken.Paragraph -> {
                    val layout = TextMeasurer.buildLayout(
                        text = token.text,
                        paint = bodyPaint,
                        width = contentWidthPt.toInt(),
                        lineSpacingMultiplier = 1.55f,
                        lineSpacingExtra = 0f,
                    )
                    var lineCursor = 0
                    while (lineCursor < layout.lineCount) {
                        val remaining = (pageHeightPt - marginPt) - state.y
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
                            startNewPage()
                            continue
                        }
                        state.drawLayoutSlice(layout, marginPt, lineCursor, lineCursor + fit, used)
                        lineCursor += fit
                        if (lineCursor < layout.lineCount) startNewPage()
                    }
                    state.y += bodyPaint.textSize * 0.6f
                }
                is ContentToken.PixivImage,
                is ContentToken.UploadedImage,
                -> {
                    // Keep layout honest without pulling gigabytes of images in.
                    val placeholder = TextMeasurer.buildLayout(
                        text = "[图片]",
                        paint = metaPaint,
                        width = contentWidthPt.toInt(),
                        lineSpacingMultiplier = 1.2f,
                        lineSpacingExtra = 0f,
                        alignment = Layout.Alignment.ALIGN_CENTER,
                    )
                    state.drawLayout(placeholder, marginPt)
                    state.y += 12f
                }
            }
        }
        state.finish(document)

        val uri = ExportUtils.saveToDownloads(context, fileName, format.mimeType) { out ->
            document.writeTo(out)
        }
        document.close()
        if (uri == null) return ExportResult.Failure("无法写入 Downloads")
        return ExportResult.Success(uri, fileName, format)
    }

    /** Tracks the current PDF page's canvas cursor (y position). */
    private class PageState {
        var currentPage: PdfDocument.Page? = null
        var pageNumber = 0
        var y: Float = 0f
        private var pageTopMargin: Float = 0f

        fun start(document: PdfDocument, width: Int, height: Int, topMargin: Float) {
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(width, height, pageNumber).create()
            currentPage = document.startPage(info)
            pageTopMargin = topMargin
            y = topMargin
        }

        fun finish(document: PdfDocument) {
            currentPage?.let { document.finishPage(it) }
            currentPage = null
        }

        fun drawLayout(layout: android.text.Layout, leftMargin: Float) {
            val canvas = currentPage?.canvas ?: return
            val save = canvas.save()
            canvas.translate(leftMargin, y)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            y += layout.height
        }

        fun drawLayoutSlice(
            layout: android.text.Layout,
            leftMargin: Float,
            startLine: Int,
            endLineExclusive: Int,
            height: Float,
        ) {
            val canvas = currentPage?.canvas ?: return
            val save = canvas.save()
            canvas.translate(leftMargin, y - layout.getLineTop(startLine).toFloat())
            canvas.clipRect(
                0f,
                layout.getLineTop(startLine).toFloat(),
                layout.width.toFloat(),
                layout.getLineBottom(endLineExclusive - 1).toFloat(),
            )
            layout.draw(canvas)
            canvas.restoreToCount(save)
            y += height
        }
    }
}
