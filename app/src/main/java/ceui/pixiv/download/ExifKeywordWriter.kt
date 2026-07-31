package ceui.pixiv.download

import android.content.Context
import android.net.Uri
import android.system.Os
import ceui.lisa.activities.Shaft
import ceui.pixiv.download.backend.SilentDownload
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * 下载完成后把作品标签写进 JPEG 的 XMP `dc:subject` —— 相册 / 图管软件(digiKam、Lightroom、
 * XnView、Windows 资源管理器「标记」、immich、Synology Photos 等)读的「关键词 / 标签」标准字段。
 *
 * 设计取舍(issue #938 评估结论):
 *  - **只处理 JPEG**。PNG 走 XMP 的库支持不可靠、ugoira=GIF 无标准关键词字段,都跳过。
 *  - 存 **XMP `dc:subject`** 而不是字面意义的「EXIF Keywords」—— 标准 EXIF 里根本没有通用的
 *    keywords 字段,`XPKeywords` 是 Windows 私有且不跨平台;`dc:subject` 才是各家相册都认的那个。
 *  - **手动插一段独立 XMP APP1**(紧跟 SOI),不引第三方库、不依赖 androidx ExifInterface 建 XMP 段
 *    的行为(它对「原本没有 XMP 的 JPEG」建段不可靠)。
 *  - 整个流程包在 [runCatching] 里:写元数据失败绝不牵连下载本身(文件此刻已在盘上)。
 *  - 开关默认关([Settings.isWriteTagsToImageExif]):每张图多一次全文件重写,只有用户显式开启
 *    才付出这次额外 IO —— 契合本项目「下载 3 万文件后变慢」的历史敏感度。
 *
 * 两个下载完成点各调一次:Manager 成功分支(旧链路 = 绝大多数下载)、ImageDetailActivity
 * 「保存这一张」(V3 直写)。两处都已在 IO 线程。
 */
object ExifKeywordWriter {

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/"

    // readBytes 会把整张图读进内存,极大原图(几十 MB)超过这个上限直接跳过,不冒 OOM 风险。
    private const val MAX_BYTES = 32L * 1024 * 1024

    // 就地重写前要求卷上至少留出「patched 大小 + 这点余量」,否则跳过,绝不冒截断后写不回去的风险。
    private const val SAFETY_SLACK = 4L * 1024 * 1024

    @JvmStatic
    fun writeIfEnabled(context: Context, uri: Uri?, fileName: String?, tagNames: List<String?>) {
        if (uri == null) return
        if (!Shaft.sSettings.isWriteTagsToImageExif()) return
        val tags = tagNames.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty()) {
            Timber.d("[ExifTags] skip: no tags name=%s uri=%s", fileName, uri)
            return
        }
        if (!isJpeg(fileName, uri)) {
            Timber.d("[ExifTags] skip: not jpeg name=%s uri=%s", fileName, uri)
            return
        }
        Timber.d("[ExifTags] begin name=%s tags=%d %s uri=%s", fileName, tags.size, tags, uri)
        runCatching {
            val resolver = context.contentResolver
            // 单次开只读 fd:同时拿文件大小(size cap 防 OOM)和所在卷可用空间(防就地重写把原图弄坏)。
            // Os.fstat / fstatvfs 比 ParcelFileDescriptor.statSize 可靠(后者对某些 provider 返 -1),
            // 且对 SD 卡 / U 盘等非主卷也拿得到真实值。
            var fileSize = -1L
            var freeBytes = -1L
            val statOk = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    fileSize = Os.fstat(pfd.fileDescriptor).st_size
                    val vfs = Os.fstatvfs(pfd.fileDescriptor)
                    freeBytes = vfs.f_bavail * vfs.f_frsize
                    true
                } ?: false
            }.getOrDefault(false)
            Timber.d("[ExifTags] stat ok=%b fileSize=%d freeBytes=%d uri=%s", statOk, fileSize, freeBytes, uri)
            // fail-safe:拿不到文件大小 / 可用空间就绝不做就地重写(截断前必须先确认放得下,否则原图丢失
            // 风险无从评估)。宁可这张不打标签,也不冒毁掉已保存图的险。本地 MediaStore/SAF/file 恒能 stat,
            // 实际几乎不会走到这;真走到说明 fd 都开不了,大概率 openInputStream 也会失败。
            if (!statOk) {
                Timber.w("[ExifTags] skip: cannot stat (size/space unknown) uri=%s", uri)
                return
            }
            if (fileSize > MAX_BYTES) {
                Timber.w("[ExifTags] skip: too large fileSize=%d > cap=%d uri=%s", fileSize, MAX_BYTES, uri)
                return
            }
            val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: run {
                Timber.w("[ExifTags] skip: openInputStream null uri=%s", uri)
                return
            }
            // 校验放这里(不放段构造里):后面靠 original 逐块写盘,不再拼一份完整 patched 数组。
            // 大原图能省掉整份拷贝 —— 峰值内存从 ~3×文件 降到 ~1×,批量并发下少 GC 抖动。
            if (original.size < 2 || (original[0].toInt() and 0xFF) != 0xFF || (original[1].toInt() and 0xFF) != 0xD8) {
                Timber.w("[ExifTags] skip: not a JPEG (no SOI FF D8) size=%d uri=%s", original.size, uri)
                return
            }
            if (containsNamespace(original)) {
                Timber.d("[ExifTags] skip: already has XMP (idempotent, no double-write) uri=%s", uri)
                return
            }
            val segment = buildApp1Segment(tags) ?: return // 内部记原因(段体过大)
            val newSize = original.size.toLong() + segment.size
            // 关键安全闸:就地重写会先把已保存的原图截断再写。若磁盘放不下新文件,截断后就写不回去 → 原图丢失。
            // 空间不够就直接跳过(原图分毫不动),宁可不打标签也不毁图。
            if (freeBytes < newSize + SAFETY_SLACK) {
                Timber.w("[ExifTags] skip: low space free=%d need=%d uri=%s", freeBytes, newSize + SAFETY_SLACK, uri)
                return
            }
            try {
                resolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(original, 0, 2)                 // SOI
                    out.write(segment)                        // 独立 XMP APP1
                    out.write(original, 2, original.size - 2) // 原文件其余部分
                }
            } catch (t: Throwable) {
                // 兜底:重写中途失败(极罕见)——把原字节写回,尽量别留半截损坏图。original 还在内存里。
                Timber.w(t, "[ExifTags] rewrite failed, rolling back original bytes uri=%s", uri)
                runCatching { resolver.openOutputStream(uri, "wt")?.use { it.write(original) } }
                    .onFailure { Timber.e(it, "[ExifTags] ROLLBACK ALSO FAILED, file may be damaged uri=%s", uri) }
                // 回滚重写同样会刷新 mtime,低调下载下也要再回拨一次(内部全 best-effort)。
                SilentDownload.backdateUri(resolver, uri)
                throw t
            }
            Timber.i("[ExifTags] OK wrote %d keywords, %d->%d bytes, uri=%s", tags.size, original.size, newSize, uri)
            // 低调下载:上面的整文件重写会把 mtime / date_modified 刷回「现在」,
            // 把刚回拨过的文件重新顶回「最近图片」前排 —— 写完再回拨一次。
            SilentDownload.backdateUri(resolver, uri)
        }.onFailure { Timber.w(it, "[ExifTags] write skipped (exception) uri=%s", uri) }
    }

    private fun isJpeg(fileName: String?, uri: Uri): Boolean {
        val name = (fileName ?: uri.lastPathSegment ?: "").lowercase()
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
    }

    /**
     * 构造一段独立 XMP APP1(marker + 长度 + null 结尾命名空间签名 + XMP packet),由调用方紧跟 SOI
     * 写进文件。只产出这一小段(~几百字节),不复制原图。段体超 64KB(极端标签量)返回 null 跳过。
     */
    private fun buildApp1Segment(tags: List<String>): ByteArray? {
        val xmp = buildPacket(tags).toByteArray(Charsets.UTF_8)
        // XMP APP1 段体以 null 结尾的命名空间签名开头(spec 规定),读端靠它识别 XMP。
        val sig = XMP_NS.toByteArray(Charsets.US_ASCII) + 0x00.toByte()
        val segLen = 2 + sig.size + xmp.size // 长度字段含自身 2 字节,不含 marker
        if (segLen > 0xFFFF) {
            Timber.w("[ExifTags] skip: xmp segment too big segLen=%d (too many tags)", segLen)
            return null
        }
        val out = ByteArrayOutputStream(segLen + 2)
        out.write(0xFF); out.write(0xE1)    // APP1 marker
        out.write((segLen ushr 8) and 0xFF) // length hi
        out.write(segLen and 0xFF)          // length lo
        out.write(sig)                      // "http://ns.adobe.com/xap/1.0/\0"
        out.write(xmp)                      // XMP packet
        return out.toByteArray()
    }

    private fun containsNamespace(src: ByteArray): Boolean {
        val needle = XMP_NS.toByteArray(Charsets.US_ASCII)
        val limit = minOf(src.size, 64 * 1024) // APPn 都在文件头,扫前 64KB 足够
        outer@ for (i in 0..(limit - needle.size)) {
            for (j in needle.indices) {
                if (src[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun buildPacket(tags: List<String>): String {
        val items = tags.joinToString("") { "     <rdf:li>${escape(it)}</rdf:li>\n" }
        return "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
            " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "  <rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
            "   <dc:subject>\n" +
            "    <rdf:Bag>\n" +
            items +
            "    </rdf:Bag>\n" +
            "   </dc:subject>\n" +
            "  </rdf:Description>\n" +
            " </rdf:RDF>\n" +
            "</x:xmpmeta>\n" +
            "<?xpacket end=\"w\"?>"
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
