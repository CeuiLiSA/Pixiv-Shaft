package ceui.pixiv.cache

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ceui.loxia.User
import ceui.pixiv.actions.PixivActions
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
                val hits =
                    if (type == MirrorContentType.USER) followedInMirror else bookmarkedInMirror
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
        ObjectPool.updateIllustTrusted(
            illust(
                illustId,
                authorId,
                bookmarked = true,
                followed = true
            )
        )
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId))

        val shown = runBlocking {
            reconcileViewerStateFromMirror(poolIllust(illustId)) { _, _ -> emptySet() }
        }
        assertTrue(shown.isBookmarked)
        assertTrue(shown.user?.is_followed == true)
        assertTrue(poolIllust(illustId).isBookmarked)
    }

    /**
     * 作者在别处已被确认「已关注」（画师主页 / 关注动作写进池），用户再从自建源榜单点进他的
     * 另一张作品：作品不可信，但关注态归作者条目管，镜像没就绪也不能把它降级成未知。
     */
    @Test
    fun `镜像没就绪_作者已确认关注_自建源作品详情页不降级关注态`() {
        val illustId = 7_006L
        val authorId = 9_006L
        ObjectPool.update(User(id = authorId, is_followed = true))
        ObjectPool.followUser(authorId)

        ObjectPool.seedUntrusted(illust(illustId, authorId, bookmarked = true, followed = false))
        val shown = runBlocking {
            reconcileViewerStateFromMirror(poolIllust(illustId)) { _, _ -> emptySet() }
        }

        assertTrue(shown.user?.is_followed == true)
        assertTrue(ObjectPool.get<User>(authorId).value?.is_followed == true)
        // 收藏态仍按「作品不可信」处理：镜像没命中 = 未知
        assertNull(shown.is_bookmarked)
    }

    /**
     * 用户本会话刚收藏过（池里权威 true），借号搜索又带回这张：出仓 bean 清成未知后经 Mapper
     * 合池，池里的 true 保留且仍可信 —— 详情页的镜像校正（镜像没就绪）不能把它抹回 null。
     */
    @Test
    fun `镜像没就绪_本会话已收藏_借号搜索合池后详情页仍是已收藏`() {
        val illustId = 7_007L
        val authorId = 9_007L
        ObjectPool.updateIllust(illust(illustId, authorId, bookmarked = true, followed = null))

        // 借号页出仓后的 bean（ViewerBookmarkState 已清洗）经 Mapper.updateIllust 合池
        ObjectPool.updateIllust(illust(illustId, authorId, bookmarked = null, followed = null))
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId))

        val shown = runBlocking {
            reconcileViewerStateFromMirror(poolIllust(illustId)) { _, _ -> emptySet() }
        }
        assertTrue(shown.isBookmarked)
        assertTrue(poolIllust(illustId).isBookmarked)
    }

    /**
     * 在自建源榜单卡（不喂池）上点红心：池里没有这条，收藏动作拿卡片 bean 兜底进池并标成可信。
     * 卡片 bean 里的作者关注态是上报者的，不能跟着一起被「可信」带进详情页。
     */
    @Test
    fun `镜像没就绪_自建源卡片上点红心_详情页不显示上报者的关注态`() {
        val illustId = 7_008L
        val authorId = 9_008L

        PixivActions.writeIllustBookmarkLocally(
            illustId,
            bookmark = true,
            illust = illust(illustId, authorId, bookmarked = false, followed = true),
        )
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId))

        val shown = runBlocking {
            reconcileViewerStateFromMirror(poolIllust(illustId)) { _, _ -> emptySet() }
        }
        assertTrue(shown.isBookmarked)
        assertNull(shown.user?.is_followed)
    }

    @Test
    fun `官方列表合池_同作者多作品可去重不重复更新用户条目`() {
        val illustId1 = 7_010L
        val illustId2 = 7_011L
        val authorId = 9_010L

        val illust1 = illust(illustId1, authorId, bookmarked = true, followed = true)
        val illust2 = illust(illustId2, authorId, bookmarked = false, followed = false)

        // 第一张作品：更新作品与作者
        ObjectPool.updateIllustTrusted(illust1, updateUser = true)
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId1))
        assertTrue(ObjectPool.isViewerStateTrusted(authorId, ceui.lisa.models.ObjectSpec.KUser))
        assertEquals(true, ObjectPool.get<User>(authorId).value?.is_followed)

        // 第二张作品（同作者）：作品入池，但跳过作者更新（模拟批次内去重）
        ObjectPool.updateIllustTrusted(illust2, updateUser = false)
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId2))
        assertEquals(illustId2, poolIllust(illustId2).id)
        // 作者的关注态保持第一次写入的权威值，未被第二张作品的 user 盖掉
        assertEquals(true, ObjectPool.get<User>(authorId).value?.is_followed)
    }

    @Test
    fun `批量合池事务_多作品同批合入且同画师自动去重`() {
        val illustId1 = 8_010L
        val illustId2 = 8_011L
        val illustId3 = 8_012L
        val authorId1 = 9_020L
        val authorId2 = 9_021L

        val illust1 = illust(illustId1, authorId1, bookmarked = true, followed = true)
        val illust2 = illust(illustId2, authorId1, bookmarked = false, followed = false)
        val illust3 = illust(illustId3, authorId2, bookmarked = true, followed = false)

        ObjectPool.updateIllustsTrusted(listOf(illust1, illust2, illust3))

        // 全部作品均已入池且 viewer 态标为可信
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId1))
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId2))
        assertTrue(ObjectPool.isIllustViewerStateTrusted(illustId3))
        assertEquals(illustId1, poolIllust(illustId1).id)
        assertEquals(illustId2, poolIllust(illustId2).id)
        assertEquals(illustId3, poolIllust(illustId3).id)

        // 作者均标为可信
        assertTrue(ObjectPool.isViewerStateTrusted(authorId1, ceui.lisa.models.ObjectSpec.KUser))
        assertTrue(ObjectPool.isViewerStateTrusted(authorId2, ceui.lisa.models.ObjectSpec.KUser))

        // authorId1 在批次内自动去重：保留首次写入的 followed=true，未被 illust2 的 followed=false 覆盖
        assertEquals(true, ObjectPool.get<User>(authorId1).value?.is_followed)
        assertEquals(false, ObjectPool.get<User>(authorId2).value?.is_followed)
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