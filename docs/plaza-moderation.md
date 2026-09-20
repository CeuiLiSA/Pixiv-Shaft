# 广场举报（2026-09-16）

帖子列表、详情顶栏和评论更多菜单均提供「举报帖子或评论」「举报作者」「屏蔽作者」。列表顶栏菜单增加「广场屏蔽名单」，可以解除屏蔽。举报成功展示服务端编号及待审核说明；重复提交已处理的举报会明确告知已处理。

举报表单为独立的 V3 页面（`TemplateRoute.PLAZA_REPORT`），复用 V3Palette 和 Montserrat。十类原因包含「广告内容」，原因必选但不预选；补充说明最多 1000 字，所有原因均可留空。输入框有主题背景、168dp 最小高度和五行空间。可选最多三张照片，使用系统 Photo Picker，不申请全相册权限。SavedStateHandle 保存目标、账号、原因、说明、照片及上传恢复信息；失败重试复用已上传媒体，成功回执可在重建后恢复。内容可滚动、宽屏限制 720dp，底部提交按钮保持可达，成功状态在页面内展示。屏蔽作者保留确认弹窗；屏蔽名单使用独立的 feeds 管理页面。

首次发帖/评论前需同意 2026-09-16 版广场规则，明确禁止色情、儿童性虐待/剥削、暴力、仇恨、骚扰、侵犯隐私、诈骗等内容。用户同意后才调用上传/发布流程。规则版本随 CreatePost 提交，后端强制校验。

屏蔽/解除屏蔽请求结束后：刷新广场安全修订号、清除请求所属账号的可观察帖子缓存、切换该账号的首屏磁盘缓存命名空间；即使切换账号或丢失响应也执行失效，因为服务端可能已完成写入。迟到的磁盘读取和图片 URL 刷新不能重新显示旧内容；旧修订号请求的 404 也不能移除刚恢复可见的帖子。API 在列表、详情、评论预览、回复计数和互动入口执行双向屏蔽。

## 原始版本验证（以下为当时记录）

- Google 渠道 `:app:testGoogleDebugUnitTest --tests 'ceui.pixiv.plaza.ui.*'`：81 项测试通过（含 Kotlin 编译）。
- `:app:lintGoogleDebug` 通过，未修改 baseline；现有 baseline 外仍有 393 项警告，包含举报编号的复数候选和规则版本日期的排版误报，并非零警告。
- 新增模型测试覆盖连续点击、网络失败保留输入与重试、账号切换、已处理举报回执、其他原因校验、屏蔽后缓存失效。
- 新增 DialogFragment 测试覆盖正常和 320dp 深色/200% 字体环境、无默认选择、可滚动、48dp 原因热区，以及 Activity 重建后原因和说明恢复。
- 新增磁盘读取竞态测试：屏蔽发生后才返回的旧缓存不得恢复。
- Launch review 新增 4 项竞态回归，修复前全部失败、修复后全部通过：屏蔽完成时切换账号、屏蔽响应丢失、旧图片请求 404、旧详情请求 404。
- Console 已以本地模拟数据运行 Playwright：1440/390/320px、深浅色、150% 字体和长文案、确认审核和历史列表。无水平溢出或页面 JS 异常。
- 尚未安装新 APK 到真机或执行生产举报/审核联调。

## Launch review 修复范围

仅审查本次举报/屏蔽/审核链路，没有纳入其他会话的贴纸改动。Android 本轮修复文件：

- `app/src/main/java/ceui/pixiv/plaza/ui/PlazaModerationDialog.kt`：屏蔽/解除屏蔽请求在 finally 中使所属账号缓存失效。
- `app/src/main/java/ceui/pixiv/plaza/ui/PlazaFeedSource.kt`、`PlazaTimelineViewModel.kt`（同目录）：在错误回写前验证修订号。
- `app/src/test/java/ceui/pixiv/plaza/ui/PlazaModerationModelTest.kt`、`PlazaFeedSourceTest.kt`、`PlazaStateTest.kt`（同目录）：上述回归测试。
- `app/src/main/res/values-ja/plaza_moderation.xml`、`values-ko/plaza_moderation.xml`、`values-ru/plaza_moderation.xml`、`values-tr/plaza_moderation.xml`、`values-zh-rTW/plaza_moderation.xml`（均在 res 下）：补齐 5 种语言的 21 条文案和 9 类原因，消除 MissingTranslation 门禁错误。
- 本文档：记录验证结果。后端性能修复及文件范围见下方审核运维说明。

检查了 DialogFragment 重建/后台完成请求、协程取消、缓存账号隔离、审核权限、幂等/冲突及证据保留。本地自动化范围内未发现尚待修复的确定性崩溃；本次没有 commit、push 或生产部署。

第二轮 review 再次检查了上述 Android 路径，未确认新的 Android 缺陷；编译、81 项相关测试及 Lint 增量门禁通过（代码未变化，任务复用上一轮结果）。本轮修复后端签名期间屏蔽造成的回复计数不一致，以及 console 长处理说明使确认按钮超出屏幕的问题；后端 317 项测试和 console 构建、长说明浏览器验证通过，详见相邻仓库审核运维说明。仅本文档在 Android 仓库更新。

后端部署配置、迁移、两机 console 转接和上架运营事项见相邻仓库 [审核运维说明](../../pixshaft-api/docs/plaza-moderation.md)。原始举报功能与公共规范、主 console 队列已完成两机部署；本次页面与照片扩展的验证见后续记录。

政策依据：[Google Play UGC](https://support.google.com/googleplay/android-developer/answer/9876937?hl=en)、[儿童安全标准](https://support.google.com/googleplay/android-developer/answer/14747720?hl=en)。代码覆盖本次广场举报链路，上架仍须核实实际审核值班、隐私披露、内容分级和适用的儿童安全申报。

## 独立页面与照片扩展

- 原因必选，新增「广告内容」；所有原因的说明均可为空。可选最多三张证据照片，已完成上传在失败重试与重建后复用，成功回执保存于 SavedStateHandle。
- Google Debug 构建、88 项广场/路由测试与 Lint 门禁通过。Lint 有 396 项既有及建议类警告，未修改 baseline。页面测试覆盖 320dp 深色/200% 字体、常规与 840dp 宽屏，以及无默认原因、输入框背景/最小高度、重建保留草稿和菜单跳转。
- Pixel 8 已安装并启动本轮 Google Debug APK；真机后续有其他操作切换到其他应用，已停止交互，未完成本轮举报页面的真机视觉验收。未提交生产测试举报。
- 后端全量 318 项通过；console 本地模拟验证 320/390/1440px、深浅主题、广告原因、空说明、三张附件来源与按需加载通过。
- Tokyo 举报模块与两端 console 静态资源已更新，备份只包含所改代码和 HTML。主 API 不重启、PID 保持 3684335，公网健康、ping、旧配置及认证队列均返回 200，匿名队列返回 401。

## 独立页面 Launch review

标的为 Android `d63a298fc` 与后端 `8ea5e33` 的独立举报页面、可选说明与照片扩展；没有审查其他会话的图标/作品页修改。检查调用入口、路由参数、ViewModel 生命周期、取消传播、照片上传与账号隔离、回执去重、旧请求/快照、服务端附件归属以及 console 证据展示，未确认需要修改业务代码的新缺陷。

补充两项模型测试：上传失败不提交部分举报，重建后使用同一上传授权；上传期间切换账号后停止后续照片和举报提交。Kotlin 编译、90 项广场/路由测试与 Lint 门禁通过（396 项警告，未改 baseline）。新增测试在本次检查期间被另一会话的已推送提交 `5e75f24eb` 一并收录；保留既有历史，不 amend 或重复提交。

后端新增旧快照没有 attachmentMediaIds 的回归；全量 319 项和 console 生产构建通过。本轮只增加测试与记录，生产业务代码及静态构建产物未变化，无需重新部署。真机页面视觉验收仍沿用上节的未完成状态，本轮未操作 Pixel 8，也未提交生产举报。

## 广场屏蔽名单完整页面

`TemplateRoute.PLAZA_BLOCKS` 打开 `PlazaBlockedUsersFragment : FeedFragment`，复用广场的标准顶栏与 720dp 限宽容器；`PlazaBlocksController` 实现 `FeedSource<Unit>`，现有 API 返回完整列表，nextCursor 为 null。首屏骨架、下拉刷新、空列表、网络错误与重试、条目复用和列表生命周期由 feeds 负责，不再维护独立列表 Adapter 或加载状态机。

条目显示用户名、UID 和解除按钮，复用 Montserrat、V3Palette、22dp 卡片与 48dp 胶囊。长名称自然换行，itemAnimator 关闭。解除请求防重复，确认成功后直接通过 feeds 移除对应项；刷新和解除串行，旧请求不会恢复已解除的记录。网络失败保留条目并恢复操作，继续沿用广场错误说明；请求完成后使原账号安全缓存失效。数据源在请求前后检查账号，恢复到已切换账号的页面时清空旧记录。

本轮验收：Google Debug 构建、25 项屏蔽名单/举报/路由测试与 Lint 门禁通过，未修改 baseline。新增 8 项测试覆盖 feeds 失败重试、刷新与解除并发、连续点击、解除失败后的恢复、账号切换和原生文字渲染下的长用户名（常规、320dp 深色/200% 字体、840dp）。Pixel 8 实测由广场顶栏打开完整页面并显示既有屏蔽记录，下拉刷新正常，崩溃缓冲区为空；未操作真实用户的解除屏蔽。真机截图位于本机 `/tmp/plaza-blocks-feed-pixel8.png`，深色和大字体使用自动化布局验证。

## 屏蔽名单 feeds 页面 Launch review

标的是本会话未提交的屏蔽名单完整页面、入口与路由、旧 Dialog/Model 中名单逻辑的迁移，以及对应资源和测试；此前已审过的举报/照片与后端不重复审查。核对 feeds 的加载、刷新、列表提交与销毁契约、全部举报/屏蔽入口调用方、账号校验、取消传播、安全缓存失效及已有路由 key，未确认新的业务缺陷。

本轮仅在 `app/src/test/java/ceui/pixiv/plaza/ui/PlazaBlockedUsersTest.kt` 增加三项回归：旧名单弹窗恢复后只跳转一次并移除自身；Activity 重建保留同一个 feeds ViewModel，进行中的解除完成后进入空态且不重复拉取；退出时取消进行中的请求，即使期间切换账号，也只使原账号安全缓存失效。没有为测试修改生产代码。

`compileGoogleDebugKotlin`、28 项屏蔽名单/举报/路由测试与 `lintGoogleDebug` 通过；Lint 有 396 项既有/建议类警告，baseline 未改。日志位于本机 `/tmp/plaza-blocks-launch-review.log`。local 模式保留工作区改动，未自动 commit/push，也未改动服务器。

## 举报页与屏蔽名单视觉重做（2026-09-17）

只动呈现层，举报/屏蔽的数据流、账号校验、缓存失效与回执语义一律不变：`PlazaModerationModel`、
`PlazaBlocksController`、`PlazaApi` 和后端没有改动。视觉规则写在
[V3 设计哲学](v3-design-philosophy.md) 的广场段落，这里只记实现落点与验收。

新增 `app/src/main/java/ceui/pixiv/plaza/ui/PlazaModerationViews.kt`，放三页共用的 V3 构件：
17/17/17/7 图标容器、分区标题加「必选 / 选填」小标、单选行末端指示器（`StateListDrawable`：
未选描边圈 / 选中实心圆加对勾）、86dp 成功徽章、说明卡、等分方格容器、虚线添加槽、首字母头像、
行内小号胶囊。`PlazaReportFragment` 与 `PlazaBlockedUsersFragment` 只组合这些构件。

- **举报页**：原因行改为连通分段行加末端指示器，`buttonDrawable = null` 去掉系统圆点但仍是
  `RadioButton`，读屏、分组和 `RadioGroup` 状态恢复不变。补充说明的卡片底挪到外层容器
  （`plaza_report_details_card`），输入框自身透明，字数计数进卡内右下。证据照片改成三个等分
  方格，下一个空位就是添加槽（`plaza_report_add_photos` 挪到这个槽上），独立的添加胶囊删除。
  提交区上方加 hairline。提交成功整页换成成功徽章 + 标题 + 举报编号。
- **文案**：`plaza_report_{reason,details,photos}_title` 改名为 `*_label` 并去掉括号里的条件，
  条件改由小标承担；新增 `plaza_report_target_label`、`plaza_form_required`、`plaza_form_optional`、
  `plaza_report_target_author`（举报作者时不再显示无关的帖子编号），七种语言同步。已无引用的
  `plaza_report_details` 删除。
- **屏蔽名单**：新增 `fragment_plaza_blocks.xml`（`plaza_column` 改竖排），列表之上常驻说明卡，
  空态因此简化为 `plaza_blocks_empty` 一句。条目改成横排：首字母头像 + 用户名/UID + 末端解除胶囊，
  骨架同步改成同构的行。`PlazaBlockedUserView.onMeasure` 给胶囊设行宽上限（卡内宽度 42%），
  只改 LayoutParams 字段不调 `setLayoutParams`，量尺寸期间不触发 `requestLayout`，判据不随调整
  变化所以不会来回翻转。
- **屏蔽确认弹窗**：内容换成同一套图标容器 + 说明，确认动作用 `ACTION_PROP_POSITIVE`
  （屏蔽可解除，不是不可逆操作，不用 danger 样式）；错误时状态行转 danger 色。
- **配色**：说明性正文从主题派生的 `textSecondary` 换成中性 `v3_text_2`。主题色只留给主操作、
  选中态和图标容器——长段落染成主题色在真机上读起来像链接。

验收：`:app:testGoogleDebugUnitTest --tests 'ceui.pixiv.plaza.ui.*' --tests 'ceui.pixiv.ui.navigation.*'`
101 项通过；`:app:lintGoogleDebug` 通过，0 error、397 warning，baseline 未改（新增的两条是
`fragment_plaza_blocks.xml` 的 Overdraw/UselessParent，与既有 `fragment_plaza_feed.xml` 同型）。
测试同步更新：举报页改为断言卡片容器带主题底、添加槽可点且带无障碍描述、选中后只有一项被选；
屏蔽条目补断言用户名列在 200% 字体 / 320dp 下仍留得住至少 64dp。

Pixel 8 实测（github debug）：举报帖子 / 举报作者两种模式、原因选中态、补充说明与证据格、
屏蔽名单（含既有记录）在浅色与深色下各截图核对。为了在不发生产举报的前提下打开未导出的
`TemplateActivity`，构建期临时给它加过 `android:exported="true"`，验完已还原并重装，
`am start` 现在按预期被 Permission Denial 拒掉。屏蔽确认弹窗因为当前账号的广场列表为空
（自身 UID 在屏蔽名单里）没有真机路径，只过了编译与单测，未做真机视觉核对。
未提交任何生产举报，未改动服务器。

### 本轮 launch review 修掉的四条

1. **辅助文字对比度不够**：「必选 / 选填」小标、举报对象标签、字数计数、输入框提示原本用
   `v3_text_3`（33% alpha），合成后实测 **2.05:1**，远低于规范要求的 4.5:1，真机上几乎读不出来。
   四处改为 `v3_text_2`（约 4.3:1）；规则写回 V3 设计哲学。
2. **证据缩略图在宽屏上糊**：格子宽度改成跟着列宽走以后，`Glide.override(dp(160))` 这个写死的解码
   尺寸在 720dp 宽屏列（每格约 220dp）下不够用——审核人员最需要看清的那张图反而是糊的。
   去掉 override，交给 Glide 按 View 实测尺寸取。
3. **成功徽章顶角被裁**：徽章转了 -8°，画出来的范围比 layout 框上下各多约 6dp，而父容器默认
   `clipChildren` + `clipToPadding` 会沿 32dp 顶部 padding 边把那两个角削平。容器关掉两个 clip。
4. **骨架与真实条目对不齐**：屏蔽名单骨架只算了列表 20dp padding、漏了卡片 16dp padding，
   解除胶囊比真实位置往右多 16dp，加载完成时会看到它左跳一下。左右各按 36dp 起算。

修完重跑：`compileGoogleDebugKotlin` 通过，101 项测试通过，`lintGoogleDebug` 0 error / 397 warning
（baseline 未改）。Pixel 8 复验三张证据照片在去掉 override 后正常加载、小标与计数已能读清。
成功徽章仍没有真机路径（要真发一条生产举报才到得了），裁切修复只有代码层依据。
