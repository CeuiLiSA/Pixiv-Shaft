package ceui.pixiv.actions

import ceui.lisa.utils.Params
import java.util.concurrent.ConcurrentHashMap

/**
 * 关注的**可见性**（公开 / 私人）——「关注了没」之外那半个事实。
 *
 * 为什么要单独一份，而不是继续塞 `AppLevelViewModel.followUserStatus`：那个 Int 把「是否关注」
 * 和「可见性」两件正交的事编码在一起（`FOLLOWED` 与 `FOLLOWED_PUBLIC` 只是同一件事的两种精度），
 * 于是任何一个写入者都能整个盖掉另一个写入者的成果。仓库为此已经长出了 `UpdateMethod` 三件套和
 * 一条 `isPreciseFollow` 守卫，而 `AppLevelViewModelHelper.fill` 拿列表里的旧 `is_followed` 覆盖
 * 用户刚点的关注，这个坑至今还挂在注释里。可见性再挤进去只会多一层补丁。
 *
 * 这里只存可见性，只有两个写入者，优先级写死在类型里：
 *  - [writeLocal]：用户在本机点出来的，最高优先级；
 *  - [writeRemote]：`user/follow/detail` 的服务端快照，**本地写过就自动丢弃**。
 *
 * 丢弃的理由是时序而不是信任：那个快照是进画师主页时发出的一次性请求，它回来时有两种情况已经
 * 过期 —— 用户在响应回来之前就点了关注（长按一秒，网络往返通常更久），或者关注请求还压在
 * [PixivActionQueue] 里没发出去（限流 / 冷却 / 断网），服务端压根还不知道。本地态会被队列一路送
 * 到服务端，终态失败还会回滚并改回这里，所以本地动过就以本地为准。
 *
 * **守卫内建在 [writeRemote] 里，不外露**：调用点没有「记得检查」这回事，也就漏不掉。
 *
 * 两张表都只增不删，量级是每个见过的用户一个 Long + 一个短字符串，和
 * `AppLevelViewModel` 那张进程级 map 同量级。
 */
object FollowVisibility {

    /** 未关注：可见性不适用。用空串而不是 null，因为 ConcurrentHashMap 不收 null。 */
    private const val NONE = ""

    private val localRestrict = ConcurrentHashMap<Long, String>()
    private val remoteRestrict = ConcurrentHashMap<Long, String>()

    /**
     * 用户在本机做出的关注 / 取关。[restrict] 传 null 表示取关（可见性不再适用）。
     *
     * 必须在触发 UI 重绘的那次写入（[ObjectPool] 的 `is_followed`）**之前**调用 —— 不是靠约定，
     * 而是靠调用位置：它在 [PixivActions.setUserFollow] 的开头，比 `writeUserFollowLocally`
     * 整个函数都早，中间隔着一层抽象，想写反得先把两个函数调用调个个儿。
     */
    fun writeLocal(userId: Long, restrict: String?) {
        localRestrict[userId] = restrict ?: NONE
    }

    /**
     * 队列判这次关注 / 取关终态失败并回滚了：本地这次操作作废，可见性一并退回「不知道」，
     * 让下一次 `user/follow/detail` 能重新填。
     *
     * 不能改写成某个具体值 —— 操作之前是公开还是私人，本地从来没存过。留空退回服务端权威，
     * 比猜一个显示出来强。
     */
    fun clearLocal(userId: Long) {
        localRestrict.remove(userId)
        remoteRestrict.remove(userId)
    }

    /**
     * `user/follow/detail` 下发的可见性。本地写过就静默丢弃（见类注释）。
     *
     * @return 是否真的写进去了 —— 调用方据此决定要不要重绘，省得白刷一次 UI。
     */
    fun writeRemote(userId: Long, restrict: String?): Boolean {
        if (localRestrict.containsKey(userId)) return false
        remoteRestrict[userId] = restrict ?: NONE
        return true
    }

    /** 这个用户当前是不是「悄悄关注中」。本地优先，其次服务端，都没有就当公开。 */
    fun isPrivate(userId: Long): Boolean =
        (localRestrict[userId] ?: remoteRestrict[userId]) == Params.TYPE_PRIVATE
}
