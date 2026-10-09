package ceui.pixiv.download.toast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadToasts] 的纯逻辑面：静音键 ↔ 枚举的映射、单条判定。
 *
 * 这里不碰设置也不弹 toast，所以是纯 JVM 测试；「设置怎么读写、旧的
 * `toastDownloadResult` 怎么迁移」走 Robolectric 的真实装载路径，见
 * `ceui.lisa.utils.DownloadToastSettingsUpgradeTest`。
 */
class DownloadToastsTest {

    @Test
    fun `空集合表示全部提示`() {
        assertTrue(DownloadToasts.quietKinds(emptySet()).isEmpty())
        assertTrue(DownloadToasts.quietKinds(null).isEmpty())
    }

    @Test
    fun `认得出的键映射回对应消息`() {
        val keys = setOf(
            DownloadToastKind.ENQUEUED.name,
            DownloadToastKind.BULK_SUMMARY.name,
            DownloadToastKind.NOVEL_PROGRESS.name,
        )
        assertEquals(
            setOf(
                DownloadToastKind.ENQUEUED,
                DownloadToastKind.BULK_SUMMARY,
                DownloadToastKind.NOVEL_PROGRESS,
            ),
            DownloadToasts.quietKinds(keys),
        )
    }

    @Test
    fun `认不出的键被忽略而不是连坐`() {
        // 降级安装 / 手改配置 / 以后删掉的消息类型都长这样：不许因为一个陌生键就把别的算进去。
        val keys = setOf("SOME_REMOVED_KIND", DownloadToastKind.ARIA2.name, "")
        assertEquals(setOf(DownloadToastKind.ARIA2), DownloadToasts.quietKinds(keys))
    }

    @Test
    fun `静音判定只看自己那一条`() {
        val keys = setOf(DownloadToastKind.DOWNLOAD_FAILED.name)
        assertTrue(DownloadToasts.isQuiet(DownloadToastKind.DOWNLOAD_FAILED, keys))
        assertFalse(DownloadToasts.isQuiet(DownloadToastKind.DOWNLOAD_DONE, keys))
        assertFalse(DownloadToasts.isQuiet(DownloadToastKind.DOWNLOAD_DONE, emptySet()))
        assertFalse(DownloadToasts.isQuiet(DownloadToastKind.DOWNLOAD_DONE, null))
    }

    @Test
    fun `每条消息的键与文案都不重复`() {
        val kinds = DownloadToastKind.entries
        assertEquals(kinds.size, kinds.map { it.name }.toSet().size)
        assertEquals(kinds.size, kinds.map { it.labelRes }.toSet().size)
    }

    @Test
    fun `弹窗的两个分组都排到了消息`() {
        for (group in DownloadToastKind.Group.entries) {
            assertTrue(
                "分组 $group 在弹窗里是空的一块",
                DownloadToastKind.entries.any { it.group == group },
            )
        }
    }
}