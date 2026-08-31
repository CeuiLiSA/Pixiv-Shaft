# :feeds —— 列表框架

全 app 的列表页（首页推荐、用户作品、榜单、收藏、评论区、动态、详情页各区块……上百个）
共用同一套骨架：`FeedSource` 出数据、`FeedRenderer` 画条目、`FeedViewModel` 管状态机、
`FeedFragment` 把两者接起来并负责刷新 / 翻页 / 空态 / 错误态 / 骨架图。

这套骨架本身与 pixiv 无关，所以从 `:app` 拆成了独立的 Android library。

---

## 1. 模块边界

`:feeds` 的 namespace 和包名都是 `ceui.pixiv.feeds`——**与拆分前完全一致**，
所以 `:app` 那 108 个消费方一行 import 都没改。

**在模块里**（对 pixiv 零依赖，只引 androidx + coroutines + gson + timber）：

| 关注点 | 类型 |
| --- | --- |
| 数据契约 | `FeedSource` / `FeedPage` / `FeedItem` / `FeedLoadPhase` |
| 状态机 | `FeedViewModel` / `FeedUiState` / `LoadState` / `feedViewModels()` |
| 渲染 | `FeedRenderer` / `feedRenderer()` / `FeedCell` / `FeedAdapter` / `AppendFooter` |
| 页面基类 | `FeedFragment` + `fragment_feed.xml` |
| 首屏骨架图 | `FeedSkeletonView` 及 6 个子类 |
| 本地优先缓存的**抽象** | `FeedCacheBackend` / `FeedCacheRecord` / `FeedFirstPageCache` / `feedCacheWriteScope` |

**留在 `:app`**：

| 东西 | 为什么不下沉 |
| --- | --- |
| `feeds/pixiv/`（`PixivFeedSource` / `replayNextUrl`） | 认识 `Client` / `KListShow`，是 pixiv nextUrl 翻页协议的桥接。代码里原本就写着「核心对 pixiv 一无所知，协议知识收在本子包」。 |
| `RoomFeedCacheBackend` / `defaultFeedCacheBackend` / `feedFirstPageCache()` | 快照表 `feed_cache_table` 和 38→39 迁移长在 `AppDatabase` 上；不该为一个可选能力把 Room 拖进框架。 |
| `ShaftFeedHost` | 主题配色 / 空态插画 / 错误文案 / Toast / 网络状态，见下。 |

依赖方向恒为 `ui → feeds.pixiv → feeds`。

## 2. 宿主怎么接进去

框架不认识 `V3Palette`、不认识 `Toaster`、不认识 `NetworkStateManager`、不认识
`getHumanReadableMessage`，但 `FeedFragment` 确实要画出合主题的刷新圈和空态、要把加载失败
说成人话。这些是**进程级、与具体页面无关**的事，所以走一个装一次的委托 `FeedHost`：

```kotlin
// Shaft.onCreate()，必须在第一个列表页创建之前
ShaftFeedHost.install()
```

`FeedHost` 的每个方法都有默认实现（framework attr 取色 / 系统 Toast / 不接网络），
不装也能跑，只是长得像个没上妆的 AOSP 列表——这保证了模块能脱离本 app 独立使用。

做成全局委托而不是 `FeedFragment` 上的 `protected open` 钩子，原因只有一个：
上百个列表页都**直接**继承 `FeedFragment`，做成钩子等于让每个页面各写一遍同样的接线。

## 3. 拆分时踩到的两件事

- **AGP 8 默认 non-transitive R**：`:app` 引用搬走的资源（`@layout/fragment_feed`、
  `@color/feed_skeleton_block` 等）必须写成 `ceui.pixiv.feeds.R.xxx`，光靠资源合并
  只在 XML 里（`@layout/fragment_feed`）自动生效，Kotlin/Java 侧不行。
- **文案随框架住在模块里**（`empty_list_1` / `list_load_failed_tap_retry` /
  `feed_error_tap_retry` / `feed_append_paused_tap_to_continue`，7 个 locale 全带）。`scripts/sort_locale_strings.py` 和
  `scripts/find_missing_used_strings.py` 的默认路径写死在 `app/src/main/res` 下，
  跑这几条要把路径显式指到 `feeds/src/main`，两个脚本参数名不同：
  `find_missing_used_strings.py` 用 `--source-root` / `--source-values` / `--target`，
  `sort_locale_strings.py` 用 `--master` / `--target`。
- **`:app:lintGithubDebug` 不再覆盖这 2500 行**：AGP 默认不 lint 依赖模块
  （`checkDependencies` 默认 false）。改了 `:feeds` 要单独跑 `./gradlew :feeds:lintDebug`。
  没给 `:app` 打开 `checkDependencies`——那会把 `:models` / `:progressmanager` /
  `:flowlayout-lib` 这些老模块的存量问题一起灌进本就是红的 app lint 里。

---

## 4. 翻页的节奏与预算（`FeedPagingPolicy`）

触底预取是零间隔的：`FeedAdapter.onBind` 进尾部 6 条就调 `loadMore()`，页一提交
`FeedFragment.rearmPaginationIfNearEnd` 再补一次。一页只剩一两条可展示（其余被 R-18 /
屏蔽等本地过滤掉，过滤发生在 `FeedSource.load` 的 mapper 里，框架只看到「薄页」）时，
那一两条一绑定就又落在预取区，于是一页接一页零间隔连翻——线上遥测里一次搜索 48 秒翻
89 页、一直翻到 pixiv 的 5000 条 offset 上限，全是这个形态，没有一个是人在看。

`FeedViewModel` 因此对**首屏之后的每一次网络翻页**（含空页追载的每一跳）过两道闸，
参数来自 `FeedSource.pagingPolicy()`（默认 `FeedPagingPolicy.Default`，全框架统一）：

- **最小间隔** `minPageIntervalMs`（默认 1 s）：相邻两次 `loadMore` 网络页至少隔这么久，
  以上一页返回时刻起算。等待发生在 append 已置 Loading 之后（footer 转圈），预取信号不会
  丢；下拉刷新照常取消它。刷新整条链路（首屏 + 空页追载）和刷新后的第一次翻页都不受限：
  那是用户的动作，追载又有跳数硬上限跑不飞，节流它只会让重过滤用户（#729）首屏多白等。
- **连翻预算** `maxAutoPages`（默认 30 页）：跑飞和人的区别不在翻了多少页，而在页与页之间
  有没有停下来看（线上人类重度翻页 12–20 s/页，跑飞 ≤ 2.4 s/页）。两页之间隔了
  `burstIdleResetMs`（默认 5 s）以上预算就归零，所以自己账号的收藏、关注动态翻多深都碰不到；
  连着翻满就置 `FeedUiState.appendPaused`，之后滚动触发一律忽略，footer 变成
  「点击加载更多」，用户点一下 `continueAppend()` 再给一份预算。预算判定排在空页追载
  （`MAX_EMPTY_PAGE_HOPS`）之前——薄页和整页滤空烧的是同一份预算。

没有网络代价的数据源（本地库）可覆写 `pagingPolicy()` 返回 `FeedPagingPolicy.Unlimited`。
与 `refreshAfterCacheHit()` 同一契约：每次现读、纯内存、不抛。
