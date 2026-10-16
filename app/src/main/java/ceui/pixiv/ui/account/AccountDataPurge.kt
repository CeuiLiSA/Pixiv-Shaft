package ceui.pixiv.ui.account

import android.content.Context
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.UserEntity
import ceui.pixiv.db.mirror.BookmarkMirrorReadyBanner
import ceui.pixiv.services.appServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 账号从本地移除时，把它在这台设备上留下的一切一次清干净。
 *
 * ## 为什么不能只删 `user_table` 那一行
 *
 * 那一行只是把钥匙。真正的体量在别处：收藏镜像（插画 / 小说 / 关注 × 公开 / 悄悄，六个书架，
 * 动辄几万行）、它的标签倒排表与续传状态、feeds 框架的首屏快照、以及按 uid 分键的私有配置。
 * 只删钥匙，这些就变成永远没人认领的孤儿 —— 界面上什么都看不见，却实打实地占着几十到几百 MB；
 * 更糟的是同一个账号以后再登录回来，它们会被当成「已有数据」继续用，其中一份还是过期的。
 *
 * ## 顺序不是随便排的
 *
 * 1. **先停镜像**（`BookmarkMirrorService.dropOwner`）：它是唯一会在清理过程中继续往表里写
 *    东西的角色，必须最先让它停手，否则刚清掉的行会被在途的那一页写回来；
 * 2. 再清首屏快照与 MMKV 标记 —— 都是纯缓存，删了最坏是下次冷启动慢一点；
 * 3. **最后删 `user_table`**：这一步之后账号在界面上就不存在了。放最后可以保证前面任何一步
 *    失败都不会留下「账号没了、数据还在」这种最难受的组合。
 *
 * 除最后一步外，每一步各自兜异常：某一步失败不该连累整个账号删不掉。
 */
object AccountDataPurge {

    private const val TAG = "AccountDataPurge"

    /**
     * 把 [targetUid] 在本设备上的全部残留资产清干净。**调用方负责切 IO**（内部也会切一次，
     * 重复切是廉价的）。正常返回时，该 uid 的凭证行已经从 `user_table` 移除。
     */
    suspend fun purgeAccountData(context: Context, targetUid: Long) {
        if (targetUid <= 0L) return
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getAppDatabase(app)

            // 1. 收藏 / 关注镜像：先摘运行态与数据，再清「补齐了」那条一次性引导的标记。
            step("停用收藏镜像", targetUid) {
                app.appServices().bookmarkMirror.dropOwner(targetUid)
            }
            step("清除收藏库引导标记", targetUid) {
                BookmarkMirrorReadyBanner.forget(targetUid)
            }

            // 2. feeds 首屏快照：cacheKey 里含 uid 命名空间，整批删。
            step("清除 feed 首屏快照", targetUid) {
                db.feedCacheDao().deleteByUid(targetUid)
            }

            // 3. 按 uid 分键的远端配置缓存（灰度搜索 / 云端翻译 / 推荐入口 / 订阅档位）。
            step("清除账号私有配置", targetUid) {
                app.appServices().remoteAppConfig.forgetUid(targetUid)
            }

            // 4. 最后才是钥匙本身。这一步不兜异常：它失败了，调用方必须知道账号其实没删掉。
            db.downloadDao().deleteUser(UserEntity().apply { userID = targetUid })
            Timber.tag(TAG).i("已从本地移除账号 %d 及其关联数据", targetUid)
        }
    }

    /** 单个步骤失败只记日志：清理是尽力而为，不该让某一步的异常把整条链路带走。 */
    private suspend fun step(label: String, uid: Long, block: suspend () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "账号 %d 的清理步骤「%s」失败，继续后续步骤", uid, label)
        }
    }
}