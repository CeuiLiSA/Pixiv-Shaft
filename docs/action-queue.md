# :actionqueue —— 持久化限流动作队列

收藏 / 取消收藏 / 关注 / 取关这类写操作，过去是**点一次发一次请求**。连点爱心、
批量关注、小组件快捷收藏都会在几百毫秒内打出多个 POST，很容易被 pixiv 429。
全链路此前没有任何节流、去重或退避（`Client.kt` / `Retro.java` 的 OkHttp 链上
只有 header 和 token 拦截器，`AppError.RateLimited` 只用来生成文案）。

这个模块把这些动作变成：**入队即返回 → 后台串行按最小间隔发送 → 撞限流整队冷却并自动
重试 → 进程被杀后下次启动继续发**。

---

## 1. 模块边界

`:actionqueue` 是一个独立的 Android library（`ceui.pixiv.actionqueue`），
**对 pixiv 零依赖** —— 不引 retrofit / okhttp / gson / `:models`。它只认识
「一个带 type 的不透明 payload 字符串」和「一个 suspend 执行器」。

依赖只有 room + coroutines。执行器（`ActionHandler`）由 `:app` 实现并注册。

### 为什么独立成 module 而不是加进 app

- **数据库隔离**：主库 `AppDatabase` 已经是 v41、19 条手写 migration、24 张表。
  加一张表就要给所有既有表担一次迁移风险。本模块用自己的
  `pixiv_action_queue.db`（v1），升级互不影响。同仓 `ChatDatabase` 就是这个先例。
- **可测**：核心调度逻辑不碰 Android，能在纯 JVM 单测里用虚拟时间跑完。

### 与仓库既有惯例的偏离（有意为之）

| 惯例 | 本模块 | 原因 |
|---|---|---|
| kapt | **KSP** | kapt 在 Kotlin 2.x 已进入维护模式；`:app` 还留着 kapt 只因 Glide 编译器没有 KSP 版 |
| `object` 单例 + `init(context)` | 可注入的普通 class | `object` 的全局可变状态跨用例泄漏，时间和存储都换不掉，等于没法单测 |
| `allowMainThreadQueries()` | 不开 | 开了只会掩盖「不小心在主线程读库」 |
| DAO 阻塞式 | 全 suspend | 所有访问都在协程里 |
| `fallbackToDestructiveMigration` | 不用，手写 migration | 队列里躺的是用户点过但还没生效的收藏，销毁式迁移会静默吃掉 |

`exportSchema = true`，`actionqueue/schemas/` **要跟着提交** —— 手写 migration 的前提。

---

## 2. 核心 API

```kotlin
// 入队请求
ActionRequest(
    type      = "illust_bookmark",       // 找 handler 用
    dedupeKey = "illust_bookmark:12345", // 合并用
    payload   = """{"id":12345,...}""",  // 队列不解释内容
    coalesce  = true,                    // 默认合并同 key 的 PENDING
    gapMs     = 0,                       // 0 = 用全局 minGapMs
)

// 执行器（app 侧实现）
fun interface ActionHandler {
    suspend fun execute(action: PendingAction): ActionOutcome
}

sealed interface ActionOutcome {
    object Success                                        // 删行
    data class Retry(retryAfterMs: Long?, cause: Throwable?)  // 整队冷却后重试
    data class Fail(reason: String, cause: Throwable?)        // 终态，不再重试
}
```

`ActionQueue` 对外还提供 `events: SharedFlow<ActionEvent>`（成功 / 重试中 / 终态失败）、
`state: StateFlow<QueueState>`、`pause()` / `resume()` / `retryAllFailed()`。

---

## 3. 主循环

```
while (isActive) {
    if (暂停 || gate 关着)          -> 睡 idlePollMs
    if (now < 冷却截止)             -> 睡到冷却结束
    if (now < 下一条允许执行时刻)    -> 睡到该时刻            ← 节流
    取一条 PENDING 且 notBefore <= now，没有就睡
    标 RUNNING → handler.execute() → 按结果 删除 / 重排 / 标失败
    下一条允许执行时刻 = now + max(minGapMs, 本条 gapMs)
}
```

唤醒靠 `Channel<Unit>(CONFLATED)`，**不用 Room 的 `Flow` 驱动 consumer** ——
`DownloadQueueDao` 的注释记过这个坑：高频 UPDATE 下 InvalidationTracker 会在首次
emit 之后静默不再触发。

---

## 4. 关键决策

### 4.1 delay 语义 = 执行间隔，不是入队后延迟

429 限制的是单位时间请求数，节流点必须在消费侧的执行间隙。所以 2 秒是
「上一条执行完 → 下一条开始执行」的最小间隔。**冷启动后第一条立即执行**，不平白等一个间隔。

### 4.2 入队合并 —— 省配额最有效的一招

连点爱心产生 收藏 → 取消 → 收藏。按 `dedupeKey` 做替换式入队（同一事务里先删同 key 的
PENDING 再插），同一目标只保留用户的**最后一次意图**，三次点击最终只发一个请求。

已经 RUNNING 的行删不掉（请求在飞），此时新行照常排队，靠 handler 幂等收敛。

### 4.3 429 → 整队冷却，不是单条退避

429 是账号级速率限制。只让失败那条退避、后面的照发，只会继续撞墙。撞到 `Retry` 时
整个 consumer 进冷却，该行 attempt+1 回 PENDING 且**保持原 id**（不丢 FIFO 位置）。

退避 30s → 60s → 120s → 300s 封顶，带 ±20% 抖动（防多任务同时恢复又一起撞）。
响应带 `Retry-After` 时以服务端为准，**即使超过封顶值也照听**。任意一次成功后清零。

### 4.4 幂等是硬契约

进程可能在「请求已发出、响应没回来」的瞬间被杀。下次启动 `resurrectRunning()` 把残留
RUNNING 复位 PENDING 重跑 —— 也就是**同一动作可能执行两次**。收藏/关注天然幂等
（pixiv 对重复收藏返回成功）。将来要接非幂等动作（发评论、发私信），必须先在 payload 里
带业务去重 id 并由服务端兜底。

### 4.5 成功即删，失败留痕

动作是瞬时的，保留 SUCCESS 行没有意义且会无限膨胀。FAILED 保留，供
`retryAllFailed()` 和排查用。

### 4.6 登录态门控

未登录时 consumer 只睡不取。否则退登状态下一整队请求全 401，白白烧完重试次数把用户
真实的收藏意图变成终态失败。

### 4.7 乐观 UI 与回滚

UI 点击后本地状态立刻改，不等网络。只有 `ActionEvent.Failed` 才回滚
（`Retrying` 还会再试，那时回滚会让爱心来回跳）。回滚前会检查当前状态是否仍等于
我们乐观写进去的值 —— 用户可能在失败前又点了一次，强行回滚会覆盖他最新的意图。

---

## 5. app 侧接线

```
Shaft.onCreate
  └─ PixivActionQueue.init(this)     // 在 SessionManager.initialize / EventReporter.init 之后
       ├─ 注册 IllustBookmarkHandler / NovelBookmarkHandler / UserFollowHandler
       ├─ gate = { SessionManager.isLoggedIn }
       ├─ start()
       └─ 订阅 events，终态失败时回滚 ObjectPool + toast
```

UI 一律走门面 `PixivActions`（`ceui.pixiv.actions`）：

```kotlin
PixivActions.toggleIllustBookmark(illust)
PixivActions.toggleNovelBookmark(novel)
PixivActions.setUserFollow(userId, follow = true, restrict = Params.TYPE_PUBLIC)
```

门面负责：乐观更新 ObjectPool → 埋点（EventReporter）→ 入队。

HTTP 状态码到 `ActionOutcome` 的翻译在 `PixivActionHandlers.kt`：
429 / 408 / 401 / 403 / 5xx / IOException → `Retry`，其余 → `Fail`。
（401/403 也重试，是因为多半只是 access token 过期；真退登了会被 gate 拦住不会空转。）

---

## 6. 迁移状态

**已迁移**（V3 的 suspend 写路径）：

- `DetailFeedSupport.toggleIllustBookmark` / `toggleNovelBookmark`
  → 调用方 `IllustSeriesFragment` / `NovelSeriesFragment` / `NovelTextFragment`
- `UActivity.followUser` / `unfollowUser`（顶层扩展函数）
  → 调用方 `UActivity` / `UserActivityV3` / `FragmentIllust` / `ArtworkSectionRenderers` /
    `IllustSeriesFeed` / `RequestPlanDetailFragment`
  顺带删掉了原先硬编码的 `delay(500L)`（等服务端落库再刷 UI，乐观更新后纯属白等）。

**未迁移**，按 429 风险从高到低：

1. `PixivOperate.postLike`（`ceui/lisa/utils/PixivOperate.java:227`）—— legacy RxJava 链，
   12 处调用点，含 feeds 卡片爱心（`IllustFeedFragment.kt:182`，连点重灾区）。
   迁移要一并处理 `ErrorCtrl` 回调、`LIKED_ILLUST` 广播、自动关注、自动下载这几个副作用。
2. `SelectTagFeedFragment.kt:245`（带标签收藏，四路 `postLike*WithTags`）
3. `NovelFeedFragment.kt:244`（小说卡片，自带进程级 scope + 收藏后自动关注）
4. `PixivOperate.postFollowUser` / `postUnFollowUser`
5. `WidgetBookmarkWorker.kt:38`（`blockingFirst()` in CoroutineWorker，失败明确不重试）

**已知但本次没动的架构裂缝**：收藏状态目前有三个真源
（`IllustsBean.is_bookmarked` 可变共享实例 / `Illust.is_bookmarked` immutable /
ObjectPool 里可能是 gson merge 出来的第三个克隆），且 legacy 链路发
`LIKED_ILLUST` 广播而 V3 suspend 链路只写 ObjectPool。本次刻意保持各路径原有的更新语义
不变，只把「发请求」这一步换成入队，好让 diff 是行为保持的。统一广播是独立的一步。

---

## 7. 可以考虑的后续

- **WorkManager 兜底**：目前 consumer 只在 app 进程存活时跑，队列在下次启动才续。
  想让「杀掉 app 也能补发」，可以加一个带网络约束的 `OneTimeWorkRequest`。
  注意两个消费者不能同时跑，需要进程级 Mutex（本 app 没声明多进程，Worker 与主循环同进程，
  一个 Mutex 就够）。
- **队列可视化**：`state` / `failedCount()` 已经暴露，可以在调试页加个类似下载队列 tab 的界面。
- **`EventReporter` 复用本模块**：它的 `queue` 是纯内存 `ArrayDeque`
  （`EventReporter.kt:97`），进程一死未上报的埋点就丢 —— 正是这个模块要解决的问题。
- **删掉 `ceui/pixiv/ui/task/BookmarkTask.kt`**：`QueuedRunnable` 的半成品子类，
  全仓无调用方，小说分支还是空 TODO。本模块已经取代它的定位。
