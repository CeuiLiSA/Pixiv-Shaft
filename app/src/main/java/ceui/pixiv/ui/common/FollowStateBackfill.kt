package ceui.pixiv.ui.common

import java.util.concurrent.ConcurrentHashMap

/**
 * 自建源上报 bean 清洗后，作者关注态处于「不可信 / 待确认」状态的轻量登记。
 *
 * 它不是可信关注态缓存：只记录「这个作者需要回源确认一次」的 id，
 * 确认成功后立即移除，不长期保存任何关注状态。
 */
object FollowStateBackfill {

    /** 自建源上报、尚未确认的 illust ID：进入详情页时默认不可信。 */
    private val untrustedIllustIds = ConcurrentHashMap.newKeySet<Long>()

    /** 从非自建源进入过详情页、且当前池数据仍可信的 illust ID。自建源重新上报会使它失效。 */
    private val trustedIllustIds = ConcurrentHashMap.newKeySet<Long>()

    /** 自建源把上报者的收藏/关注态清掉后调用，登记该作品需要回源确认。 */
    fun markIllustUntrusted(illustId: Long) {
        if (illustId > 0L) {
            untrustedIllustIds.add(illustId)
            // 自建源一旦重新上报，池里的关注态可能已被这份“不可信快照”覆盖，
            // 之前从非自建源进入攒下的 trusted 标记必须作废，否则会跳过回补。
            trustedIllustIds.remove(illustId)
        }
    }

    /** 该作品当前是否处于「自建源上报、等待详情页确认」状态。 */
    fun isIllustUntrusted(illustId: Long): Boolean = untrustedIllustIds.contains(illustId)

    /** 非自建源进入同 ID 详情页时，把该 illust 标记为可信池成员。 */
    fun markIllustTrusted(illustId: Long) {
        if (illustId > 0L && !untrustedIllustIds.contains(illustId)) trustedIllustIds.add(illustId)
    }

    /** 确认完成（信任池命中或 detail 回补成功）后：移除不可信标记，并加入可信池。 */
    fun markIllustConfirmed(illustId: Long) {
        if (illustId > 0L) {
            untrustedIllustIds.remove(illustId)
            trustedIllustIds.add(illustId)
        }
    }

    /** 自建源进入详情页时，先用它判断能否直接信任池里的同 ID bean。 */
    fun isIllustTrusted(illustId: Long): Boolean = trustedIllustIds.contains(illustId)
}