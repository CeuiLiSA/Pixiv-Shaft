package ceui.pixiv.feeds

/**
 * 列表条目的最小数据契约。
 *
 * 实现方推荐用 immutable data class：
 * - [feedKey] 提供身份（identity），同一种条目内必须稳定且唯一，驱动 DiffUtil 的移动/增删判定；
 * - 内容比较（是否需要重绑）直接依赖 data class 的 equals()。
 *
 * 条目身份 = (具体类型, feedKey)，所以不同类型的条目允许使用相同的 key，互不干扰。
 */
interface FeedItem {

    /** 同类条目内全局唯一且稳定的身份，例如作品 id。 */
    val feedKey: Any
}

/** 条目完整身份：类型 + key，跨类型不会互相碰撞。 */
internal val FeedItem.identity: Any
    get() = Pair(javaClass, feedKey)

/** 按身份去重，保留首个出现的条目；DiffUtil 依赖身份唯一，重复 key 会导致 diff 结果错乱。 */
internal fun List<FeedItem>.dedupByIdentity(): List<FeedItem> {
    val seen = HashSet<Any>(size)
    return filter { seen.add(it.identity) }
}

/**
 * 整代替换时，能不能放心交给 DiffUtil —— 还是必须走「同步清空 + 整段插入」把它绕开。
 *
 * 交给 DiffUtil 会出两种毛病，各有各的成因，判据要同时挡住：
 *
 * 1. **撕位置**：两代共有的条目若被**重排**，DiffUtil 会派发 move，而 StaggeredGridLayoutManager
 *    对 move + 整行重排有已知缺陷（本仓 `:app` 的 `StaggeredManager` 就在吞它抛的
 *    IndexOutOfBounds）→ 列表塌成零散卡片 + 黑色空档再重排。
 * 2. **旧数据赖着不走**：两代若**完全不重合**，DiffUtil 会全删 + 全插。它是**后台 diff**，
 *    落地时 itemAnimator 早已恢复 → 旧内容淡出与新内容淡入同屏 = 「旧数据往上顶一下再消失」。
 *    清空 + 整段插入则是同步快路，动画关得住，换得干脆。
 *
 * 反过来，**有共有项且没被重排**就是安全的：这说明两代是同一批内容的更新，不是换了一批。
 * 这时绝不能清空重填 —— 清空会让每个 holder 走 recycle、把
 * `:app` 的 `staggerIllustRenderer` 的 imageRequestKey 清掉，于是每张卡都重发一次
 * Glide 请求、白闪一下；而那个 tag 恰恰就是为了跳过「图其实没变」的重载才加的。逐个场景：
 *
 * - **某人的最新作品 / 某天的日榜**：刷一百次 id 和顺序都一样 → 常规路径，就地重绑内容变化
 *   （浏览数等），Glide 请求被 tag 守卫跳过 → 全程不闪。
 * - **翻了 3 页共 90 条后下拉刷新，只拉回第 1 页 30 条**：共有项就是那 30 条、两边顺序一致 →
 *   常规路径。DiffUtil 移除折叠线以下的 60 条，顶部原地重绑，用户看不到跳动。
 * - **作者刚发了新作插到最前 / 某条被删**：共有项相对顺序不变 → 常规路径，干净地插一条 / 删一条。
 * - **切了筛选范围（动态页 全部/公开/私人）、换了 tab**：两代完全不重合 → 走清空重填。
 * - **榜单名次变了 / 推荐流部分重合又换了位置**：共有项被打乱 → 走清空重填。
 *
 * O(n)，只在换代时算一次。刷新是用户主动、低频操作，这点代价换掉一次全屏闪烁很划算。
 */
internal fun needsCleanSwapAcrossGenerations(old: List<FeedItem>, new: List<FeedItem>): Boolean {
    if (old.isEmpty() || new.isEmpty()) return false
    val newIdentities = new.mapTo(HashSet(new.size)) { it.identity }
    val survivorsInOldOrder = old.mapNotNull { it.identity.takeIf(newIdentities::contains) }
    // 完全不重合 = 换了一批内容，见上面第 2 条
    if (survivorsInOldOrder.isEmpty()) return true
    val oldIdentities = old.mapTo(HashSet(old.size)) { it.identity }
    val survivorsInNewOrder = new.mapNotNull { it.identity.takeIf(oldIdentities::contains) }
    // 两边取的是同一批条目（互为交集），长度必然相等；顺序不同即意味着 DiffUtil 会派发 move
    return survivorsInOldOrder != survivorsInNewOrder
}
