# Firebase Analytics（GA4）埋点方案

代码入口：`app/src/main/java/ceui/pixiv/analytics/`（`Analytics` 服务、`ScreenTracker`、`AnalyticsClassifiers`）。
**加事件、加参数、改取值先改这份文档，再改代码**；命名限制由 `AnalyticsContractTest` 锁住。

---

## 1. 为什么改

改造前 GA4 里只有 SDK 自动事件（`screen_view` / `user_engagement` / `session_start` / `first_open` /
`app_remove` / `app_exception` / `os_update`），回答不了任何产品问题：

- `screen_view` 只认 Activity 类名。一百多个 `TemplateActivity` 路由挤在 `TemplateActivity` 一行，
  首页五个 tab 都是 `MainActivity`——报表里排第一的「页面」其实是几十个不同的页面。
- 没有任何自定义事件。收藏、关注、下载、搜索、登录这些核心行为有没有人用、从哪个入口来、
  失败率多少，全都看不到。

## 2. 三路埋点各管什么

| | 回答什么 | 带内容 id / 用户输入 | 去哪 |
|---|---|---|---|
| **GA4（本文）** | 功能有没有人用、从哪个入口来、转化掉在哪一步、失败面 | **不带** | Google |
| `EventReporter` | 社区热度榜的原始数据 | 带作品 id | 自家服务端 |
| `Nana7miSearchTelemetry` | 服务端代搜的逐请求链路 | 带搜索词 | 自家服务端 |

口径向后两者看齐：

- 收藏 / 关注和 `EventReporter` 一样，**服务端确认之后**才记（`PixivActionQueue.report()`），
  用户连点、被新点击覆盖、终态失败回滚的都不算。
- 失败原因和 Nana7mi 遥测用**同一套词**（见 §5 `reason`），两边报表能直接对照。
- 和 Nana7mi 一样走 Hilt `@Singleton` + `ServicesProvider`，Java 侧
  `ServiceProviderKt.appServices(ctx).getAnalytics()`，Kotlin 侧 `ctx.appServices().analytics`。

## 3. 隐私红线

1. **不发身份**：不调 `setUserId`，不带 pixiv uid，不带作品 / 小说 / 用户 id。GA4 的
   `app_instance_id` 配上内容 id 就能拼出单个人的完整浏览记录。
2. **不发用户输入**：搜索词、评论、标签原文一律不进参数，只记「搜了几个词」这类形状。
3. **不发单件内容的分级**：作品的 R-18 / AI 标记不进参数。
   页面级的导航结构不在此列——首页 R-18 tab 的页面名是 `home_r18`（它是产品入口，和其余 tab
   同等看待），但不会出现任何「这一件作品是 R-18」的信号。
4. 用户在「试验性设置」里关掉 Firebase 后什么都不发（SDK 侧 `setAnalyticsCollectionEnabled(false)`，
   `Analytics.isEnabled()` 也提前短路）。

## 4. 页面浏览（screen_view）

manifest 里关掉了 SDK 的自动上报（`google_analytics_automatic_screen_reporting_enabled=false`），
由 `ScreenTracker`（`ActivityLifecycleCallbacks`）手动报：

| 参数 | 值 |
|---|---|
| `screen_name` | 逻辑页面名，snake_case |
| `screen_class` | 仍是 Activity 类名——和改造前的历史数据同口径，按类名看的报表不断档 |
| `surface` | 进入本页之前的浏览页（见下），首屏没有 |

**页面名从哪来**

| 页面 | `screen_name` |
|---|---|
| `TemplateActivity` | 路由常量名小写：`TemplateRoute.NOVEL_READER` → `novel_reader`。**常量名因此是埋点契约**，改名会让这一页的历史断开 |
| `MainActivity` | 按当前 tab：`home_recommend` / `home_discover` / `home_following` / `home_r18` / `home_me`；切 tab 也记一次 |
| `VActivity` | `illust_detail` |
| `ImageDetailActivity` | `image_viewer` |
| `SearchActivity` | `search_results` |
| `UActivity` / `UserActivityV3` | `user_profile`（新旧版靠 `screen_class` 区分） |
| `RankActivity` | `ranking` |
| `OutWakeActivity` | `deep_link`（外链 / 通知 / 浏览器登录回调的中转页） |
| `OutReversActivity` | `share_receiver` |
| 其余 Activity | 类名去掉 `Activity` 后缀转 snake_case：`AppLockActivity` → `app_lock` |

触发时机对齐 SDK 原来的行为：换到另一个 Activity（含返回）记一次，熄屏再亮这种「没换页」的
resume 不记。每次 screen_view 同时把 Crashlytics 自定义键 `screen` 设成当前页面名。

**surface（入口归因）**

`surface` = 把用户领到当前页的那个**浏览页**的 `screen_name`。看内容的页面（详情、大图、阅读器、
评论、点赞列表、作者资料、AI 放大 / 抠图预览、举报…，完整列表见 `ScreenTracker.CONTENT_SCREENS`）
不算浏览页，它的 `surface` 按**它被打开那一刻**定，回到它时恢复。所以在详情里点收藏、在阅读器里点下载，
带出去的都是把用户领进来的那个列表页；推荐流 → 详情 → 作者主页 → 返回详情再收藏，入口仍是推荐流。
「哪个入口带来的收藏」直接按 `surface` 切。

除 `screen_view` 外的所有事件都自动带 `surface`。收藏 / 关注是异步确认的，`surface` 和 `method`
在**点击那一刻**写进动作队列的 payload（`AnalyticsOrigin`），确认时原样带出——用户点完立刻
换页、进程被杀后下次启动才确认，归因都不会错。老版本入队的动作没有这个字段，就不带。

## 5. 事件字典

| 事件 | 什么时候记 | 参数 |
|---|---|---|
| `illust_view` | 插画详情翻到一幅作品 | `content_type`、`nav` |
| `bookmark` | 收藏 / 取消收藏**服务端确认** | `content_type`、`action`、`visibility`（仅 add）、`method` |
| `follow` | 关注 / 取关**服务端确认**（公开↔悄悄切换不记） | `action`、`visibility`（仅 add）、`method` |
| `action_fail` | 收藏 / 关注打满重试后终态失败、界面回滚（被新点击覆盖的不算；进程内最多 30 条） | `action_type`、`reason` |
| `download` | 下载**入队**（不等结果） | `content_type`、`method`、`item_count` |
| `download_fail` | 插画单页 / 小说单篇下载失败（进程内最多 30 条） | `content_type`、`reason` |
| `search` | 发起一次搜索（GA4 推荐事件名，但**不带** `search_term`） | `search_type`、`query_kind`、`trigger`、`term_count` |
| `login_begin` | 用户真正发起登录（打开浏览器 / 提交 token / 导入） | `method` |
| `login` | 登录成功（GA4 推荐事件名） | `method` |
| `login_fail` | 登录失败 | `method`、`reason` |
| `logout` | 退出登录（被踢下线每进程只记一次） | `reason` |
| `load_error` | 列表加载失败、错误文案展示给了用户（同一异常只记一次，进程内最多 50 条） | `reason` |

所有事件另带 `surface`（见 §4）。

**参数取值**

| 参数 | 取值 |
|---|---|
| `content_type` | `illust` / `manga` / `ugoira` / `novel` / `mixed`（批量入队，类型混合）。`download_fail` 的插画管线拿不到作品类型，漫画 / 动图也记 `illust` |
| `nav` | `open`（从列表点进来的第一幅）/ `swipe`（详情里左右滑到的）。两者之比 = 详情页连刷深度 |
| `action` | `add` / `remove` |
| `visibility` | `public` / `private` |
| `method`（bookmark） | `single` / `with_tags` / `bulk` |
| `method`（follow） | `manual` / `auto_after_bookmark` |
| `method`（download） | `direct`（单个作品 / 单篇小说）/ `bulk`（批量选择、作者全部作品、收藏夹；小说多选也算）/ `series`（小说系列整部）/ `auto_after_bookmark`（小说收藏后自动下载；插画的自动下载走同一入口，记 `direct`） |
| `method`（login*） | `oauth` / `signup`（仅 `login_begin`；浏览器回调分不出入口，结果统一记 `oauth`）/ `refresh_token` / `import` |
| `item_count` | 本次入队数量：插画是页数，小说是篇数，批量是作品数（页数要等消费者展开才知道） |
| `action_type` | 动作队列的 type：`illust_bookmark` / `novel_bookmark` / `user_follow` |
| `search_type` | `illust` / `novel` / `user`（结果页当前 tab） |
| `query_kind` | `keyword` / `url`（粘贴链接直达）/ `numeric_id`（纯数字按 id 直达） |
| `trigger` | `navigation`（带着关键词进搜索页：点标签、热门标签、深链…）/ `in_page`（在搜索页里回车、改 chip、换筛选） |
| `term_count` | 关键词按空白拆开的个数，区分单标签浏览和组合筛选 |
| `reason`（失败类） | `http_<状态码>`、`decode_json`、`network_timeout` / `network_dns` / `network_tls` / `network_connect` / `network_socket` / `network_cronet` / `network_io` / `network_cancelled`、`unexpected`；下载另有 `storage_full` / `storage_permission` / `storage_unavailable`；登录另有 `missing_code` / `missing_verifier` / `persist_failed` |
| `reason`（logout） | `user` / `token_invalid` |

**几条口径**

- 下载记**请求**：它没有服务端真值可等，点了就是一次产品行为。成功量 ≈ 请求页数 − 失败页数。
  不逐页记成功：一次作者全部作品的批量下载能有上万页，会让一个人淹掉所有人。
- 失败类事件有进程级配额：断网时一屏几十个请求同时失败、批量下载连着失败几百页，不设上限会把
  真实分布淹掉。看失败**率**用「有失败的会话占比」，不要拿事件数除以请求数。
- `reason` 只按异常**类型**和状态码分档，不读响应正文、不拿 message 当值（正文是一次性的，
  错误文案映射还要读它判 Cloudflare 拦截；message 是无限基数）。

## 6. 用户属性

| 属性 | 取值 | 何时更新 |
|---|---|---|
| `login_state` | `logged_in` / `guest` | 登录 / 切号 / 退登 |
| `pixiv_premium` | `true` / `false`（未知时清空，不猜） | 同上 |
| `app_channel` | `github` / `google` | 冷启动 |
| `theme_mode` | `light` / `dark` / `system` | 冷启动 |
| `detail_ui` | `v3` / `classic` | 冷启动 |
| `app_locale` | 语言 tag，如 `zh-CN`；跟随系统时 `system` | 冷启动 |

设置类属性改了要到下次冷启动才更新，按人群切分足够了。

## 7. GA4 后台要做的（一次性）

自定义参数不注册成自定义维度 / 指标，在探索和标准报表里**看不到**（DebugView 里能看到）。
注册只对之后的数据生效，越早越好。

**管理 → 自定义定义 → 自定义维度（范围：事件）**

`surface`、`nav`、`action`、`visibility`、`action_type`、`reason`、`search_type`、`query_kind`、`trigger`

（`method`、`content_type` 是 GA4 预定义维度，不用注册。）

**自定义指标（范围：事件，单位：标准）**

`item_count`、`term_count`

**自定义维度（范围：用户）**

`login_state`、`pixiv_premium`、`app_channel`、`theme_mode`、`detail_ui`、`app_locale`

**关键事件**：`bookmark`、`follow`、`download`、`login`。

免费版上限是事件维度 50 个、用户维度 25 个、指标 50 个，目前远没到。

## 8. 调试

```bash
# 实时看事件进 DebugView（debug 包名 ceui.pixiv.cshaft，release 是 ceui.pixiv.pshaft）
adb shell setprop debug.firebase.analytics.app ceui.pixiv.cshaft
# debug 包每条事件都会打一行
adb logcat -s Analytics
# 用完关掉
adb shell setprop debug.firebase.analytics.app .none.
```

## 9. 加一个事件

1. 先在本文 §5 写清楚：什么时候记、参数、取值、口径。
2. `Analytics.Event` / `Param` 加常量并登记进 `ALL`；取值有限的参数做成带 `wire` 的 enum。
3. 在 `Analytics` 上加一个按业务命名的方法，调用方只传业务对象，不在调用点拼 Bundle。
4. 跑 `AnalyticsContractTest`（名字长度、snake_case、保留名）。
5. 新参数去 GA4 后台注册（§7）。

不要：拼动态字符串当参数值（超过维度基数上限后全进 `(other)`）、在调用点直接调
`FirebaseAnalytics.logEvent`、为了埋点改 `:feeds` 框架或动作队列的语义。
