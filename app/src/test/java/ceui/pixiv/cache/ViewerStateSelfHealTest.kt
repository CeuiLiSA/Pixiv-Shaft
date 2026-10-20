package ceui.pixiv.cache

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.utils.reconcileViewerStateFromMirror
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自建源详情页的收藏 / 关注态，在「镜像没就绪」与「镜像就绪」两种世界里的收场方式。钉住四件事：
 *
 *  1. 自建源 bean 进池前被清成**未知**（null），不是 false —— false 会盖掉池里权威的 true；
 *  2. 未知在屏上渲染成「未收藏 / 未关注」（[Illust.isBookmarked] 与 `is_followed == true`）；
 *  3. 镜像没就绪：换一条非自建源的路重新进同一页（`v1/illust/detail` 整体覆盖）后，两个态都被
 *     权威值覆盖并重新标成可信 —— 也就是「自愈」；
 *  4. 镜像就绪：进页当场按镜像补完 —— 真相是已收藏已关注就上屏双 true；真相是没有就仍是 false，
 *     且池里存的是「未知」而不是 false。
 *
 * 镜像查询经 [reconcileViewerStateFromMirror] 的 mirrorLookup 参数注入，不碰 Room 与全局服务。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ViewerStateSelfHealTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    /**
     * 真相是「已收藏 + 已关注」：自建源上报的快照里这两个态是别人的（这里给 false），
     * 进池后必须清成未知；等权威 detail 回来再补成 true。
     */
    @Test
    fun `真相已收藏已关注_自建源清空后由权威 detail 自愈并入池`() {
        val illustId = 7_001L
        val authorId = 9_001L

        ObjectPool.seedUntrusted(illust(illustId, authorId, bookmarked = false, followed = false))

        val sanitized = poolIllust(illustId)
        assertNull(sanitized.is_bookmarked)
        assertNull(sanitized.user?.is_followed)
        // 上屏：未知渲染成未收藏 / 未关注，而不是把别人的状态画出来
        assertFalse(sanitized.isBookmarked)
        assertFalse(sanitized.user?.is_followed == true)
        assertFalse(ObjectPool.isIllustViewerStateTrusted(illustId))

        // 重新进入非自建源提供的同一页：fetchFullIllustDetail 的落池方式（整体覆盖 + 作者条目）
        ObjectPool.update(
            illust(illustId, authorId, bookmarked = true, followed = true),
            isFullVersion = true,
        )
        ObjectPool.update(User(id = authorId, is_followed = true))

        val healed = poolIllust(illustId)
        assertTrue(healed.isBookmarked)
        assertTrue(healed.user?.is_followed == true)
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId))
        // 作者栏读的是共享的 User 条目，它也得跟着自愈
        assertTrue(ObjectPool.get<User>(authorId).value?.is_followed == true)
    }

    /**
     * 真相是「未收藏 + 未关注」，自建源却谎报「已收藏 + 已关注」：进池时双清成未知，
     * 权威 detail 回来后仍保持未收藏 / 未关注，且不能留下脏的 true。
     */
    @Test
    fun `自建源谎报已收藏已关注_清成未知后权威 detail 仍是未收藏未关注`() {
        val illustId = 7_002L
        val authorId = 9_002L

        ObjectPool.seedUntrusted(illust(illustId, authorId, bookmarked = true, followed = true))

        val sanitized = poolIllust(illustId)
        assertNull(sanitized.is_bookmarked)
        assertNull(sanitized.user?.is_followed)
        assertFalse(sanitized.isBookmarked)
        assertFalse(sanitized.user?.is_followed == true)

        ObjectPool.update(
            illust(illustId, authorId, bookmarked = false, followed = false),
            isFullVersion = true,
        )
        ObjectPool.update(User(id = authorId, is_followed = false))

        val healed = poolIllust(illustId)
        assertFalse(healed.isBookmarked)
        assertEquals(false, healed.user?.is_followed)
        assertEquals(false, ObjectPool.get<User>(authorId).value?.is_followed)
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId))
    }

    /**
     * 两个镜像都就绪、真相是「已收藏 + 已关注」：自建源详情页按镜像补完，上屏就是双 true，
     * 而且写回池 —— 作者栏和收藏 FAB 读的都是池。
     */
    @Test
    fun `两个镜像都就绪_真相已收藏已关注_自建源详情页上屏双 true`() {
        val illustId = 7_003L
        val authorId = 9_003L
        ObjectPool.seedUntrusted(illust(illustId, authorId, bookmarked = false, followed = false))

        val bookmarkedInMirror = setOf(illustId)
        val followedInMirror = setOf(authorId)
        val shown = runBlocking {
            reconcileViewerStateFromMirror(poolIllust(illustId)) { type, ids ->
                val hits = if (type == MirrorContentType.USER) followedInMirror else bookmarkedInMirror
                ids.filterTo(mutableSetOf()) { it in hits }
            }
        }

        assertTrue(shown.isBookmarked)
        assertTrue(shown.user?.is_followed == true)
        assertTrue(poolIllust(illustId).isBookmarked)
        assertTrue(ObjectPool.get<User>(authorId).value?.is_followed == true)
        // 镜像补出来的值不算「权威确认」：detail 回来仍可整条覆盖
        assertFalse(ObjectPool.isIllustViewerStateTrusted(illustId))
    }

    /**
     * 两个镜像都就绪、真相是「未收藏 + 未关注」：自建源谎报的双 true 被镜像压回去，
     * 上屏仍是 false —— 而且是**未知**（null）渲染出来的 false，不是把 false 写进池。
     */
    @Test
    fun `两个镜像都就绪_真相未收藏未关注_自建源谎报也被镜像压回 false`() {
        val illustId = 7_004L
        val authorId = 9_004L
        ObjectPool.seedUntrusted(illust(illustId, authorId, bookmarked = true, followed = true))

        val shown = runBlocking {
            reconcileViewerStateFromMirror(poolIllust(illustId)) { _, _ -> emptySet() }
        }

        assertFalse(shown.isBookmarked)
        assertFalse(shown.user?.is_followed == true)
        // 只升不降：镜像没命中 = 不知道，不是「确定没收藏」
        assertNull(shown.is_bookmarked)
        assertNull(shown.user?.is_followed)
        assertFalse(poolIllust(illustId).isBookmarked)
        assertFalse(ObjectPool.get<User>(authorId).value?.is_followed == true)
    }

    /**
     * 生产里的「自愈」不是靠 `v1/illust/detail`，而是靠官方列表合池：从首页推荐 / 关注流点进
     * 同一页时，列表 bean 的当前用户视角会经 [ObjectPool.updateIllustTrusted] 覆盖池里的未知。
     * 这里钉住两件事：覆盖会生效，而且详情页的镜像校正（镜像没就绪）不会把它抹回 null。
     */
    @Test
    fun `镜像没就绪_官方列表合池补上真值后详情页不会再抹回未知`() {
        val illustId = 7_005L
        val authorId = 9_005L
        ObjectPool.seedUntrusted(illust(illustId, authorId, bookmarked = false, followed = false))
        assertNull(poolIllust(illustId).is_bookmarked)

        // 官方列表页合池（IllustFeedPoolSync 的写法）
        ObjectPool.updateIllustTrusted(illust(illustId, authorId, bookmarked = true, followed = true))
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId))

        val shown = runBlocking {
            reconcileViewerStateFromMirror(poolIllust(illustId)) { _, _ -> emptySet() }
        }
        assertTrue(shown.isBookmarked)
        assertTrue(shown.user?.is_followed == true)
        assertTrue(poolIllust(illustId).isBookmarked)
    }

    private fun illust(
        id: Long,
        authorId: Long,
        bookmarked: Boolean?,
        followed: Boolean?,
    ): Illust = Illust(
        id = id,
        is_bookmarked = bookmarked,
        user = User(id = authorId, is_followed = followed),
    )

    private fun poolIllust(id: Long): Illust =
        requireNotNull(ObjectPool.get<Illust>(id).value) { "池里没有 illustId=$id" }
}