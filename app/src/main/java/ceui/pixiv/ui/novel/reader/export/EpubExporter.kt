package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.paginate.ImageResolver
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hand-rolled EPUB 2.0 writer — no third-party library. The archive layout:
 *
 *   mimetype                       (STORED, uncompressed, must be first entry)
 *   META-INF/container.xml         (points at OPF)
 *   OEBPS/content.opf              (manifest + spine)
 *   OEBPS/toc.ncx                  (NCX table of contents)
 *   OEBPS/content.xhtml            (the entire novel, chapters anchored)
 *   OEBPS/images/<id>.jpg          (embedded images, one per [pixivimage]/[uploadedimage])
 *
 * Images are fetched synchronously via Glide and re-encoded as JPEG. Network
 * failures degrade gracefully: the chapter still exports, images become
 * inline placeholders.
 */
class EpubExporter : NovelExporter {
    override val format: ExportFormat = ExportFormat.Epub

    override suspend fun export(
        context: Context,
        novel: Novel?,
        webNovel: WebNovel,
        tokens: List<ContentToken>,
        fileName: String,
    ): ExportResult {
        val resolver = ImageResolver.of(webNovel)
        val title = (novel?.title ?: webNovel.title.orEmpty()).ifEmpty { "novel" }
        val author = novel?.user?.name.orEmpty()
        val novelId = novel?.id?.toString() ?: webNovel.id.orEmpty().ifEmpty { System.currentTimeMillis().toString() }
        val caption = ExportUtils.brToNewline(webNovel.caption)

        // Resolve image bytes up front. We key by synthesised filename so the
        // XHTML and manifest agree.
        data class BundledImage(val fileName: String, val bytes: ByteArray, val mime: String)
        val images = linkedMapOf<String, BundledImage>()
        for (token in tokens) {
            val url = resolver(token) ?: continue
            val key = when (token) {
                is ContentToken.UploadedImage -> "uploaded_${token.imageId}"
                is ContentToken.PixivImage -> "pixiv_${token.illustId}_${token.pageIndex}"
                else -> continue
            }
            if (images.containsKey(key)) continue
            val bitmap = ExportUtils.loadBitmap(context, url) ?: continue
            val jpeg = ExportUtils.bitmapToJpeg(bitmap)
            images[key] = BundledImage("$key.jpg", jpeg, "image/jpeg")
        }

        val chapterHtml = buildChapterXhtml(title, author, caption, tokens, resolver, images)
        val opf = buildContentOpf(novelId, title, author, images)
        val ncx = buildTocNcx(novelId, title, tokens)

        val uri = ExportUtils.saveToDownloads(context, fileName, format.mimeType) { out ->
            ZipOutputStream(out).use { zip ->
                // mimetype must be the first entry and STORED (uncompressed).
                storeEntry(zip, "mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
                deflateEntry(zip, "META-INF/container.xml", CONTAINER_XML.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/content.opf", opf.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/toc.ncx", ncx.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/content.xhtml", chapterHtml.toByteArray(Charsets.UTF_8))
                for ((_, img) in images) {
                    deflateEntry(zip, "OEBPS/images/${img.fileName}", img.bytes)
                }
            }
        } ?: return ExportResult.Failure("无法写入 Downloads")

        return ExportResult.Success(uri, fileName, format)
    }

    private fun buildChapterXhtml(
        title: String,
        author: String,
        caption: String,
        tokens: List<ContentToken>,
        resolver: (ContentToken) -> String?,
        images: Map<String, Any>,
    ): String {
        fun imageKey(token: ContentToken): String? = when (token) {
            is ContentToken.UploadedImage -> "uploaded_${token.imageId}"
            is ContentToken.PixivImage -> "pixiv_${token.illustId}_${token.pageIndex}"
            else -> null
        }
        val chapterAnchors = mutableListOf<Pair<String, String>>() // (anchor, title)
        var chapterCounter = 0
        val body = buildString {
            append("<h1>${escape(title)}</h1>\n")
            if (author.isNotEmpty()) append("<p class=\"meta\">作者: ${escape(author)}</p>\n")
            if (caption.isNotEmpty()) {
                append("<div class=\"caption\"><p>")
                append(escape(caption).replace("\n", "</p><p>"))
                append("</p></div>\n")
            }
            append("<hr/>\n")
            for (token in tokens) {
                when (token) {
                    is ContentToken.Paragraph -> {
                        append("<p>${escape(token.text)}</p>\n")
                    }
                    is ContentToken.BlankLine -> append("<br/>\n")
                    is ContentToken.PageBreak -> append("<hr/>\n")
                    is ContentToken.Chapter -> {
                        chapterCounter += 1
                        val anchor = "ch$chapterCounter"
                        chapterAnchors += anchor to token.title
                        append("<h2 id=\"$anchor\">${escape(token.title)}</h2>\n")
                    }
                    is ContentToken.PixivImage -> {
                        val key = imageKey(token)
                        if (key != null && images.containsKey(key)) {
                            append("<p class=\"image\"><img src=\"images/$key.jpg\" alt=\"pixiv ${token.illustId}\"/></p>\n")
                        } else {
                            val url = resolver(token) ?: ""
                            append("<p class=\"image\"><em>[图片 pixiv ${token.illustId}: $url]</em></p>\n")
                        }
                    }
                    is ContentToken.UploadedImage -> {
                        val key = imageKey(token)
                        if (key != null && images.containsKey(key)) {
                            append("<p class=\"image\"><img src=\"images/$key.jpg\" alt=\"uploaded ${token.imageId}\"/></p>\n")
                        } else {
                            val url = resolver(token) ?: ""
                            append("<p class=\"image\"><em>[图片 ${token.imageId}: $url]</em></p>\n")
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
        images: Map<String, Any>,
    ): String {
        val imageManifest = images.keys.joinToString("\n    ") { key ->
            """<item id="img_$key" href="images/$key.jpg" media-type="image/jpeg"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${escape(title)}</dc:title>
    <dc:creator opf:role="aut">${escape(author.ifEmpty { "Pixiv" })}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId" opf:scheme="pixiv">$novelId</dc:identifier>
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

    private fun buildTocNcx(novelId: String, title: String, tokens: List<ContentToken>): String {
        val chapters = tokens.filterIsInstance<ContentToken.Chapter>()
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
    <meta name="dtb:uid" content="$novelId"/>
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
            val crc = CRC32().apply { update(data) }
            this.crc = crc.value
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

    private companion object {
        const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""
    }
}
