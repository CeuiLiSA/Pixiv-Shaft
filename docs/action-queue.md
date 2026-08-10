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

// 归属：行入队时会记下 owner（app 侧传登录 uid），取行 / 计数 / 合并一律按它过滤。
// 库跨登录态持久，不分归属的话 A 没发完的收藏会用 B 的 token 发出去。
ActionQueue(..., owner = { SessionManager.loggedInUid.toString() })

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
`state: StateFlow<QueueState>`、`pause()` / `resume()` / `retryAllFailed()` /
`forget(id)`（处理完失败反馈后删行）/ `clearFailed()`。

`ActionEvent.Failed` 带 `supersededByPending`：同 `dedupeKey` 上还压着更新的意图，
此时**不要回滚**，见 4.7。

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
响应带 `Retry-After` 时以服务端为准，即使超过指数退避的封顶值也照听，但采信有天花板
`maxRetryAfterMs`（30 分钟）—— 一个 `Retry-After: 86400` 能把整队冻到进程结束，
而冻住期间用户每次收藏都只是变红然后石沉大海，界面上看不出队列已经停了。任意一次成功后清零。

**冷却值落库**（`queue_meta` 表），不是只放内存：冷却动辄几分钟，而「后台待几分钟被系统
回收」恰恰是这个窗口里最可能发生的事。只存内存的话，下次启动会带着一整队 `notBefore=0`
的行原地重新撞同一个还没过期的账号级限流。

但读回来时要**钳掉墙钟错乱写出的离谱值**。落库的是绝对时刻而 `Clock.SYSTEM` 是墙钟：
RTC 失效的设备开机常带一个偏前的时间，此时撞一次 429 写下的截止时刻就是「偏前的时间 + 冷却」，
等 NTP 把时钟校回来，`now < cooldownUntilMs` 从此恒真。而清零冷却只有「某条动作执行成功」和
`retryAllFailed()` 两条路，前者被冻着就不可能发生 —— 队列会静默停摆到重装为止。
`start()` 按 `QueuePolicy.maxPossibleCooldownMs`（指数退避封顶叠抖动 与 `maxRetryAfterMs` 取大，
即单次冷却的上确界）钳一刀：合法值按定义就在这个上界内，一个都不会被削短。
**钳完写回库**——只改内存的话每次冷启动都会照着坏值重新钳一轮，白冻到进程结束；写回去的是绝对
时刻，真实时间一过就自愈。

合并入队时，被删掉的 PENDING 行若正在退避（`attempt > 0` / `notBefore` 在未来），新行
**继承**它们最大的 `attempt` 和 `notBefore`。不继承的话，用户对着一个一直失败的目标反复点，
每点一次重试预算就清零一次，这条动作永远到不了 `maxAttempts`，既不判终态失败也不回滚，
红心一直挂着，而每次尝试都在把整队冷却顶起来、连累其他所有收藏和关注。

### 4.4 幂等是硬契约

进程可能在「请求已发出、响应没回来」的瞬间被杀。下次启动 `resurrectRunning()` 把残留
RUNNING 复位 PENDING 重跑 —— 也就是**同一动作可能执行两次**。收藏/关注天然幂等
（pixiv 对重复收藏返回成功）。将来要接非幂等动作（发评论、发私信），必须先在 payload 里
带业务去重 id 并由服务端兜底。

### 4.5 成功即删，失败反馈完也删

动作是瞬时的，保留 SUCCESS 行没有意义且会无限膨胀。FAILED 短暂保留，等 app 侧把回滚和
提示做完后由 `forget(id)` 删掉 —— 目前没有任何界面能看到 FAILED 行，留着只会带着最长
500 字的错误文本一直堆积。上个进程遗留的 FAILED 在启动时统一清掉：它们的乐观状态随进程
一起没了，重启后界面上的收藏态本来就是服务端真值，既回滚不了也没人会去看。

### 4.5b 存储层故障不许带走消费者

`step()` 里所有 `store.*` 调用都在 try/catch 里。磁盘满 / 库损坏时如果让异常冒出去，
loopJob 会死在没有 `CoroutineExceptionHandler` 的 scope 上 —— 既崩进程，又让这个进程
剩下的时间里每次收藏都只写了乐观状态却永远发不出去。出错就上报 `onError` 并睡一轮再试。

入队同理：`enqueue()` 把请求投进 `Channel(UNLIMITED)`，由**单个**消费协程按顺序写库。
不能各自 `scope.launch` —— 那些协程落在多线程 IO 池上，连点两下（收藏、取消）时后发的
可能先落库，随后先发的那条再把它合并掉，结果队列发的是「收藏」而界面显示「未收藏」，
且永远不会自愈。

### 4.6 登录态门控 + 账号归属

未登录时 consumer 只睡不取。否则退登状态下一整队请求全 401，白白烧完重试次数把用户
真实的收藏意图变成终态失败。

光判「有没有登录」不够：库是跨登录态持久的，`Common.logOut` 也不杀进程。A 连点二十个
收藏、队列才发完四条就退登、换 B 登录 —— 剩下十六条会用 B 的 token 发出去，收藏进 B 的
账号。所以每行都记 `owner`（入队时的登录 uid），取行 / 计数 / 合并一律按它过滤；A 的行
在 A 再次登录时才继续发。

### 4.7 乐观 UI 与回滚

UI 点击后本地状态立刻改，不等网络。只有 `ActionEvent.Failed` 才回滚
（`Retrying` 还会再试，那时回滚会让爱心来回跳）。

判断「这条失败还该不该回滚」的依据是 `supersededByPending`，**不是比较当前值**：
收藏 → 取消 → 收藏之后，当前值和失败那条恰好相等，比值会误判成可以回滚，把用户还没发出去
的最新意图覆盖掉。队列在广播 Failed 前查一次「同 owner 同 dedupeKey 上还有没有 PENDING」，
有就置位，UI 直接跳过回滚（连提示都不弹 —— 用户看到的就是他最后一次点的状态）。
比值的守卫仍然保留，挡的是队列之外改过状态的路径（例如详情页刚从服务端刷回真值）。

**不在点击时报埋点。** 那一刻请求还没出去，之后可能因为限流打满重试或作品已删除而终态
失败并被回滚，而埋点发出去撤不回来（协议里没有反向事件），社区热度榜就会按一堆从未发生过
的收藏来排序。埋点改在 `ActionEvent.Succeeded` 时发。

**不在点击时弹「成功」toast。** 同理：队列可能正在冷却、也可能被闸门挡着，此时报成功是骗
用户，而几分钟后终态失败还会再补一个「操作失败」自相矛盾。反馈由按钮/爱心本身承担。

---

## 5. app 侧接线

```
Shaft.onCreate
  └─ PixivActionQueue.init(this)     // 在 SessionManager.initialize / EventReporter.init 之后
       ├─ 注册 IllustBookmarkHandler / NovelBookmarkHandler / UserFollowHandler
       ├─ gate = { SessionManager.isLoggedIn }
       ├─ owner = { SessionManager.loggedInUid.toString() }
       ├─ start()
       ├─ 清掉上个进程遗留的 FAILED 行
       └─ 订阅 events：成功时补埋点；终态失败时回滚 ObjectPool + toast + forget 该行
          （订阅 scope 自带 CoroutineExceptionHandler，且每条事件单独 try/catch ——
           一条处理不了的事件不能把整个订阅带走，否则此后所有失败都不再回滚也不再提示）
```

UI 一律走门面 `PixivActions`（`ceui.pixiv.actions`）：

```kotlin
PixivActions.toggleIllustBookmark(illust)
PixivActions.toggleNovelBookmark(novel)
PixivActions.setUserFollow(userId, follow = true, restrict = Params.TYPE_PUBLIC)
```

门面负责：乐观更新 ObjectPool → 入队。埋点由队列在服务端确认之后补发。

收藏的默认可见性走 `PixivActions.defaultBookmarkRestrict()`，读「私密收藏」设置 ——
仓库里每个收藏入口都尊重这个开关，门面自己写死 public 等于把用户明确要求保密的收藏
公开挂到主页上。

**批量入口**（`BulkBookmarkEnqueue`，批量选择页底栏，issue #974）也只是循环调同一个门面，
不另起写路径。它额外做三件门面管不了的事：剔掉已经是目标态的项（否则 toast 上的数字不等于
真正会发出去的请求数）、分块 `yield` 让主线程（每项都要写几个 ObjectPool 表示再发一条广播）、
用进程级 scope（调用方入队后立刻 `finish()`）。一次几百项意味着队列要按 2 秒间隔跑十几分钟，
所以确认框里明写预计耗时 —— 不说的话用户会当成没生效。

HTTP 状态码到 `ActionOutcome` 的翻译在 `PixivActionHandlers.kt`：
429 / 408 / 400 / 401 / 403 / 5xx / IOException → `Retry`，其余 → `Fail`。
400 也重试：它正是 pixiv 表达「access token 过期」用的码，正常由
`TokenFetcherInterceptor` 在链内刷新重放，能漏到这里说明那次刷新自己失败了（刚连上网时
refresh 超时等）。判终态失败的话，一次网络抖动就会让爱心弹回去，而那时 token 多半已经
刷好、同一个请求两秒后就能成功。真是参数非法，五次之后照样收敛成终态失败。

---

## 6. 迁移状态

**已迁移**（V3 的 suspend 写路径）：

- `DetailFeedSupport.toggleIllustBookmark` / `toggleNovelBookmark`
  → 调用方 `IllustSeriesFragment` / `NovelSeriesFragment` / `NovelTextFragment`
- `NovelFeedFragment.toggleNovelLike`（小说卡片；收藏后自动关注也一并入队）
- `NovelReaderV3ViewModel.toggleBookmark`（V3 阅读器；它和详情页拿同一个 ObjectPool 当真值，
  一边排队一边直发会互相删对方的收藏）
- `UActivity.followUser` / `unfollowUser`（顶层扩展函数）
  → 调用方 `UActivity` / `UserActivityV3` / `FragmentIllust` / `ArtworkSectionRenderers` /
    `IllustSeriesFeed` / `RequestPlanDetailFragment`
  顺带删掉了原先硬编码的 `delay(500L)`（等服务端落库再刷 UI，乐观更新后纯属白等）。

**已迁移**（legacy RxJava 写路径，现在都只是 `PixivActions` 的薄封装）：

- `PixivOperate.postLike`（12 处调用点，含 feeds 卡片爱心 `IllustFeedFragment`，连点重灾区）
- `PixivOperate.postFollowUser` / `postUnFollowUser`
- `SelectTagFeedFragment.submitStar`（按标签收藏）—— 带标签和不带标签打的是**同一个**
  `bookmark/add` 端点，是互相覆盖不是叠加，所以共用 dedupeKey。标签随 payload 走
  （`BookmarkPayload.tags`，追加字段，老行反序列化为 null 正好落到不带标签那一支）。
  顺带修掉：此前它只发 `LIKED_*` 广播、不写 ObjectPool，按标签收藏之后读池渲染的 V3 详情页
  那颗心还是灰的。
- `PixivOperate.postLikeNovel(NovelBean, String, View)` 已**删除**（全仓无调用方，
  留着只是给下一个人一个绕开队列的现成入口）。

**未迁移**：

1. `WidgetBookmarkWorker.kt:38`（`blockingFirst()` in CoroutineWorker，失败明确不重试）。
   它不写 ObjectPool 也不发广播，且与队列并行——「app 内取消收藏（进队列，正在冷却）→
   小组件收藏（直发）」会以相反顺序落到服务端。

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
- **队列可视化**：模块侧 `state` / `failedCount()` / `retryAllFailed()` 都在，但 app 侧目前
  没有任何界面用它们，所以 `PixivActionQueue` 没有把它们转出去（转出去也是死代码）。
  真要做调试页时再加，届时 4.5 的「反馈完即删」要改成保留失败行。
- **`EventReporter` 复用本模块**：它的 `queue` 是纯内存 `ArrayDeque`
  （`EventReporter.kt:97`），进程一死未上报的埋点就丢 —— 正是这个模块要解决的问题。
- **无归属行与陈旧行的清理**：退登期间入队的行 owner 记的是 `"0"`，旧账号的行同理 ——
  既不会被执行也不会被 `clearFailed(owner)` 收走。且队列没有时效，半年前排下的 PENDING
  在用户重新登录后照发。`ActionEntity.createdAt` 已经落库但没有任何查询读它，
  加一次启动期按 `createdAt` 的扫尾即可。
- **断网判定 fail-open**：`NetworkMonitor.isConnected` 在 `SecurityException`（部分 OEM ROM）
  时返回 `true`。那类机器上真断网会被判成「在线 IOException」→ 计入重试预算 →
  7.5 分钟烧完转终态失败并回滚，正是 `countsAsAttempt` 要避开的场景。
