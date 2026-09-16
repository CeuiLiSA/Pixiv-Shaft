# 广场举报（2026-09-16）

帖子列表、详情顶栏和评论更多菜单均提供「举报帖子或评论」「举报作者」「屏蔽作者」。列表顶栏菜单增加「广场屏蔽名单」，可以解除屏蔽。举报成功展示服务端编号及待审核说明；重复提交已处理的举报会明确告知已处理。

举报表单复用 WitDialog 和 V3Palette：无预选原因、九类原因、最多 1000 字说明（其他原因必填）、48dp 选项热区、内部滚动和 Montserrat 字体。DialogFragment + SavedStateHandle 保存目标、所属账号、原因与说明；ViewModel 执行请求，防连续点击，失败保留输入。成功反馈只在 RESUMED 状态处理，避免后台完成请求时提交 Fragment 事务。

首次发帖/评论前需同意 2026-09-16 版广场规则，明确禁止色情、儿童性虐待/剥削、暴力、仇恨、骚扰、侵犯隐私、诈骗等内容。用户同意后才调用上传/发布流程。规则版本随 CreatePost 提交，后端强制校验。

屏蔽/解除屏蔽请求结束后：刷新广场安全修订号、清除请求所属账号的可观察帖子缓存、切换该账号的首屏磁盘缓存命名空间；即使切换账号或丢失响应也执行失效，因为服务端可能已完成写入。迟到的磁盘读取和图片 URL 刷新不能重新显示旧内容；旧修订号请求的 404 也不能移除刚恢复可见的帖子。API 在列表、详情、评论预览、回复计数和互动入口执行双向屏蔽。

## 验证

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

后端部署配置、迁移、两机 console 转接和上架运营事项见相邻仓库 [审核运维说明](../../pixshaft-api/docs/plaza-moderation.md)。代码不等于生产已部署；公共规范 URL 和举报队列需先完成 Tokyo 与主 API 两端部署。

政策依据：[Google Play UGC](https://support.google.com/googleplay/android-developer/answer/9876937?hl=en)、[儿童安全标准](https://support.google.com/googleplay/android-developer/answer/14747720?hl=en)。代码覆盖本次广场举报链路，上架仍须核实实际审核值班、隐私披露、内容分级和适用的儿童安全申报。
