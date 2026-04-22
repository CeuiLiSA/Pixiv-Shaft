package ceui.pixiv.download

import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.ConfigPresets
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.DownloadItem
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.model.RelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 用户反馈：自定义命名规则后下载的文件名里好像没出现 ID。
 * 这里把「设置自定义模板 → plan → 拿到最终路径」的端到端链路用单测固定下来，
 * 防止以后哪一层默默把模板替换/退回到默认值。
 */
class CustomTemplateRenderTest {

    private val meta = ItemMeta(
        id = 12345L,
        title = "夜の物語",
        author = Author(id = 67890L, name = "Alice"),
        createdAt = Instant.parse("2024-05-01T12:34:56Z"),
        page = 0,
        totalPages = 3,
        width = 1920,
        height = 1080,
        flags = emptySet(),
    )

    private val illustItem = DownloadItem(
        bucket = Bucket.Illust,
        ext = "png",
        mime = "image/png",
        sourceUrl = "",
        meta = meta,
    )

    private val novelItem = DownloadItem(
        bucket = Bucket.Novel,
        ext = "txt",
        mime = "text/plain",
        sourceUrl = "",
        meta = meta,
    )

    private val saneStorage = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)

    private fun cfg(template: String, perBucket: Map<Bucket, BucketConfig> = emptyMap()) =
        DownloadConfig(
            defaults = BucketDefaults(template = template, storage = saneStorage, overwrite = OverwritePolicy.Rename),
            perBucket = perBucket,
        )

    /**
     * 全模板自定义：作者/作品 ID/标题/扩展名都应当原样出现在最终路径里。
     * 反向覆盖用户的怀疑——「ID 不在文件名里」。
     */
    @Test fun `custom template includes id author and title verbatim`() {
        val facade = Downloads(cfg("Shaft/{author}/{id}_{title}.{ext}"), { NoopBackend() })
        val plan = facade.plan(illustItem)
        assertEquals(listOf("Shaft", "Alice", "12345_夜の物語.png"), plan.path.segments)
    }

    /** 多页插画：page1（1-based）应该正确替换。 */
    @Test fun `page1 variable substitutes 1 based page index`() {
        val multi = illustItem.copy(meta = meta.copy(page = 4))
        val facade = Downloads(cfg("{author}/{id}_p{page1}.{ext}"), { NoopBackend() })
        val plan = facade.plan(multi)
        assertEquals(listOf("Alice", "12345_p5.png"), plan.path.segments)
    }

    /** 用户在「小说」桶上单独覆写模板，全局默认应当被覆盖。 */
    @Test fun `per bucket override beats default for novel bucket`() {
        val facade = Downloads(
            cfg(
                template = "default/{id}.{ext}",
                perBucket = mapOf(
                    Bucket.Novel to BucketConfig(template = "ShaftNovels/{author}/{id}_{title}.{ext}"),
                ),
            ),
            { NoopBackend() },
        )
        val plan = facade.plan(novelItem)
        assertEquals(listOf("ShaftNovels", "Alice", "12345_夜の物語.txt"), plan.path.segments)
    }

    /**
     * 默认模板（Shaft 经典风格）下，作者目录 + ID + 标题三件套都应该在最终路径里出现。
     * 这条单测的目的：哪天我们改默认模板，万一不小心把 {id} 删掉，CI 立刻红。
     */
    @Test fun `shaft classic preset default keeps id in output path`() {
        val cfg = ConfigPresets.of(ConfigPresets.Id.ShaftClassic, saneStorage, saneStorage)
        val facade = Downloads(cfg, { NoopBackend() })
        val plan = facade.plan(illustItem)
        assertTrue(
            "shaftClassic 模板必须包含作品 ID，实际 path = ${plan.path.joinTo()}",
            plan.path.joinTo().contains("12345"),
        )
    }

    /** 路径分隔符出现在变量值里时，FsSanitizer 应当替换为下划线，而不是新建子目录。 */
    @Test fun `slash in title is sanitized into underscore not new directory`() {
        val dirty = illustItem.copy(meta = meta.copy(title = "evil/../boom"))
        val facade = Downloads(cfg("{title}_{id}.{ext}"), { NoopBackend() })
        val plan = facade.plan(dirty)
        assertEquals(listOf("evil_.._boom_12345.png"), plan.path.segments)
    }

    /** 未知变量不该让整个下载崩溃 —— 现在依赖外层 try/catch（Manager.insert）兜底。 */
    @Test(expected = IllegalStateException::class)
    fun `unknown variable still throws so caller catch fires`() {
        val facade = Downloads(cfg("{nope}.{ext}"), { NoopBackend() })
        facade.plan(illustItem)
    }

    private class NoopBackend : StorageBackend {
        override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle =
            error("not used")
        override fun exists(relPath: RelativePath): Boolean = false
        override fun delete(relPath: RelativePath): Boolean = false
    }
}
