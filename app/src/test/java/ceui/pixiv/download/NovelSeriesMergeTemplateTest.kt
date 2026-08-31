package ceui.pixiv.download

import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.ConfigPresets
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.sanitize.FsSanitizer
import ceui.pixiv.download.template.DefaultTemplates
import ceui.pixiv.download.template.PageNumbering
import ceui.pixiv.download.template.Template
import ceui.pixiv.download.template.TemplateSamples
import ceui.pixiv.download.template.TemplateValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * #964 后续 —— 合并下载的合集文件独立成 [Bucket.NovelSeries]：文件名和目录都可
 * 由模板决定，`{chapters}` 报章节数。
 */
class NovelSeriesMergeTemplateTest {

    private val OFF = PageNumbering(indexFrom1 = true, padded = false)

    private fun mergeMeta(chapters: Int? = 12, title: String = "My Series") = ItemMeta(
        id = 987654L,
        title = title,
        author = Author(id = 7L, name = "Alice"),
        createdAt = Instant.parse("2024-01-02T03:04:05Z"),
        flags = setOf(Flag.Series),
        seriesTitle = title,
        seriesTotal = chapters,
    )

    @Test fun `default merge template reproduces the legacy series filename`() {
        val t = Template.compile(DefaultTemplates.NOVEL_SERIES)
        assertEquals(
            "Shaft/Novels/NovelSeries_987654_Chapter_1~12_My Series.txt",
            t.render(mergeMeta(), "txt", OFF).joinTo(),
        )
    }

    @Test fun `merge template follows the export format extension`() {
        val t = Template.compile(DefaultTemplates.NOVEL_SERIES)
        assertTrue(t.render(mergeMeta(), "epub", OFF).filename.endsWith(".epub"))
    }

    @Test fun `chapter count renders empty when unknown`() {
        val t = Template.compile("x{chapters}y.txt")
        assertEquals("xy.txt", t.render(mergeMeta(chapters = null), "txt", OFF).joinTo())
    }

    /** 用户诉求：合集不再被钉死在系列子目录里，可以自己挑作者目录。 */
    @Test fun `custom template can park the merge under the author folder`() {
        val t = Template.compile("Shaft/Novels/{author} ({author_id})/{series} 合集 1~{chapters} {id}.{ext}")
        assertEquals(
            "Shaft/Novels/Alice (7)/My Series 合集 1~12 987654.txt",
            FsSanitizer.clean(t.render(mergeMeta(), "txt", OFF)).joinTo(),
        )
    }

    @Test fun `merge bucket resolves independently of the novel bucket`() {
        val cfg = DownloadConfig(
            defaults = BucketDefaults(
                template = "Illust/{id}.{ext}",
                storage = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images),
            ),
            perBucket = mapOf(
                Bucket.Novel to BucketConfig(
                    template = "Shaft/Novels/[?series:{series}/]{title} {id}.txt",
                    storage = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads),
                ),
                Bucket.NovelSeries to BucketConfig(template = "Shaft/Merged/{series} {id}.{ext}"),
            ),
        )
        assertEquals("Shaft/Merged/{series} {id}.{ext}", cfg.resolve(Bucket.NovelSeries).template)
        assertNotEquals(
            cfg.resolve(Bucket.Novel).template,
            cfg.resolve(Bucket.NovelSeries).template,
        )
    }

    /**
     * 升级用户的持久化配置里没有 NovelSeries 条目：模板要掉回合集自己的默认值
     * （而不是 defaults 里的插画模板），存储位置要跟着小说走（而不是图片卷）。
     */
    @Test fun `legacy config without a merge entry falls back sanely`() {
        val images = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
        val downloads = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
        val cfg = DownloadConfig(
            defaults = BucketDefaults(template = "Illust/{id}.{ext}", storage = images),
            perBucket = mapOf(
                Bucket.Novel to BucketConfig(template = "Shaft/Novels/{title}.txt", storage = downloads),
            ),
        )
        val resolved = cfg.resolve(Bucket.NovelSeries)
        assertEquals(DefaultTemplates.NOVEL_SERIES, resolved.template)
        assertEquals(downloads, resolved.storage)
    }

    @Test fun `every preset ships a valid merge template`() {
        val images = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
        val downloads = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
        for (id in ConfigPresets.Id.entries) {
            val cfg = ConfigPresets.of(id, images, downloads)
            val bucket = cfg.perBucket[Bucket.NovelSeries]
            assertTrue("Preset $id has no NovelSeries entry", bucket?.template?.isNotBlank() == true)
            assertEquals("Preset $id merge storage", downloads, cfg.resolve(Bucket.NovelSeries).storage)
            val result = TemplateValidator.validate(bucket!!.template!!, Bucket.NovelSeries)
            assertTrue("Preset $id merge template invalid: ${result.errors}", result.ok)
        }
    }

    /**
     * 合集桶的兜底逻辑写在通用的 [DownloadConfig.resolve] 里，必须证明它对其他桶
     * 是零影响：没有 override 的桶照旧全部掉回 defaults，一个字节都不能变。
     * [Bucket.Backup] 是另一处刻意例外（模板缺省用 [DefaultTemplates.BACKUP]、
     * 覆盖策略固定 Rename），单独断言。
     */
    @Test fun `other buckets keep the plain defaults fallback`() {
        val images = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
        val cfg = DownloadConfig(
            defaults = BucketDefaults(template = "Illust/{id}.{ext}", storage = images),
        )
        val exempt = setOf(Bucket.NovelSeries, Bucket.Caption, Bucket.Backup, Bucket.TempCache)
        for (bucket in Bucket.entries - exempt) {
            val resolved = cfg.resolve(bucket)
            assertEquals("bucket $bucket template", "Illust/{id}.{ext}", resolved.template)
            assertEquals("bucket $bucket storage", images, resolved.storage)
            assertEquals("bucket $bucket overwrite", cfg.defaults.overwrite, resolved.overwrite)
        }
        val backup = cfg.resolve(Bucket.Backup)
        assertEquals(DefaultTemplates.BACKUP, backup.template)
        assertEquals(images, backup.storage)
        assertEquals(OverwritePolicy.Rename, backup.overwrite)
    }

    /** 预设里插画 / 动图 / 小说三条模板不能被这次改动碰到。 */
    @Test fun `presets keep their illust ugoira and novel templates`() {
        val images = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
        val downloads = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
        val classic = ConfigPresets.shaftClassic(images, downloads)
        assertEquals(DefaultTemplates.ILLUST, classic.resolve(Bucket.Illust).template)
        assertEquals(DefaultTemplates.UGOIRA, classic.resolve(Bucket.Ugoira).template)
        assertEquals(DefaultTemplates.NOVEL, classic.resolve(Bucket.Novel).template)

        val bySeries = ConfigPresets.bySeries(images, downloads)
        assertEquals(
            "Shaft/Novels/[?series:{series}/{series_order} ]{title} {id}.txt",
            bySeries.resolve(Bucket.Novel).template,
        )
        assertEquals(images, bySeries.resolve(Bucket.Illust).storage)
    }

    /** `{chapters}` 对插画 / 单篇小说样本渲染成空串，不会炸掉别的桶的模板。 */
    @Test fun `chapters variable is inert outside the merge bucket`() {
        val t = Template.compile("Shaft/{title} {id}{chapters}.{ext}")
        assertEquals(
            "Shaft/夏日_祭り・花火 123456789.jpg",
            FsSanitizer.clean(t.render(TemplateSamples.ILLUST_SAMPLE, "jpg", OFF)).joinTo(),
        )
    }

    @Test fun `settings preview renders the merge sample`() {
        val preview = TemplateSamples.preview(DefaultTemplates.NOVEL_SERIES, Bucket.NovelSeries)
        assertTrue(preview is TemplateSamples.Preview.Ok)
        assertEquals(
            "Shaft/Novels/NovelSeries_1234567_Chapter_1~12_Example Series.txt",
            (preview as TemplateSamples.Preview.Ok).cleaned.joinTo(),
        )
    }
}
