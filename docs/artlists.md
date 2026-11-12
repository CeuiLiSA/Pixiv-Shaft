# 画单

用户创建公开画单，把插画放在一起；其他用户可以浏览、收藏画单，收藏后看到创建者持续更新的内容。

## 第一版行为

- 「发现 → 画单」进入公开画单；「我的 / 侧边栏 → 我的画单」进入自己创建的画单。
- 三个范围：发现画单、我创建的、我收藏的。列表按创建 / 收藏顺序分页。
- 插画卡长按、V3 作品详情更多菜单提供「加入画单」。选择已有画单，或创建后加入。
- 名称必填、简介选填；所有画单公开。一个作品可加入多个画单，同一画单内去重。
- 默认封面取最早加入的可显示作品；创建者可长按作品设为封面、移除作品。
- 只有创建者可以编辑、删除。收藏画单引用原画单，不复制作品；创建者更新后收藏者看到最新内容。
- 分享链接为 `https://pixshaft.com/artlists/{id}`，网页提供 `shaftintent://artlist?id={id}` 唤起。
- 每个账号最多 200 个画单，每单最多 1000 个作品。只接受公开且可查看的作品。

## Android 实现

`ui/artlist` 的两个列表都基于 **Feeds**：`ArtlistsFragment : FeedFragment`、
`ArtlistDetailFragment : IllustFeedFragment`。骨架、下拉刷新、分页、错误重试和列表状态使用现有框架。
详情的画单头是 full-span feed item，作品复用现有插画 renderer；详情页与列表续页通过 `ListIllust` 衔接。

作品接口直接返回现有结构，不引入第二套作品 DTO：

```json
{
  "illusts": [{ "id": 123, "type": "illust", "image_urls": {}, "meta_pages": [] }],
  "next_url": "https://pixshaft.com/v1/artlists/1/works?after=30&limit=30&r18=0"
}
```

画单信息走独立 `Artlist`：id、uid、name、description、ownerName、workCount、saveCount、saved、cover。
作品图片、标签、作者、原图分页字段沿用 `Illust`。共享数据剔除上传者的收藏、关注和屏蔽状态；
客户端用自己的镜像和现有详情链路恢复状态。公共数据不直接写入可信 ObjectPool。
画单封面与内容遵循当前账号的分级、标签、画师和单作品屏蔽设置。
封面使用精简 `Illust`，不携带整部作品的分页图址。封面没有揭示遮罩，因此单作品屏蔽或 AI 模糊命中时显示占位，
并订阅屏蔽名单变化；作品卡片仍使用现有遮罩交互。

所有写操作携带发起账号 uid，后端与 Bearer 主体校验；请求完成后客户端再次检查账号。
表单草稿、创建幂等键存在 SavedStateHandle，旋转不丢输入。删除和移除有确认，失败保留内容与重试入口。
画单变更的刷新版本与列表一起保存在 ViewModel，后台编辑后重建页面不会漏掉刷新。
详情头的浏览按钮和过滤空态从整张 Feed 及分页结束状态派生，不能固定使用首屏的结果。

## V3 视觉

沿用「作品 / 作者 / 阅读」配方：封面 → 标题 → 创建者 → 简介和计数 → 浏览 / 收藏。
复用 `V3Palette`、日夜资源、Montserrat 真实字重、22dp 容器和 48dp 以上按钮热区。
列表、详情和表单统一使用 `toolbar_layout` 配合 `setupV3Toolbar`，由顶栏处理状态栏 Insets。
内容列在宽屏最多 720dp；详情头随列表滚动，长文案和大字体可自然增高。
表单可滚动并处理键盘 Insets；列表不做条目入场动画。

## 验证与部署

- Android：`assembleGithubDebug`、`assembleGithubDebugAndroidTest`、`lintGithubDebug`、
  `ArtlistContractTest`、`TemplateRouteTest`、safe 模块认证测试。画单文案覆盖现有 7 种非默认语言资源。
- Pixel 8：`ArtlistDeviceTest` 使用隔离本地后端验证真实 Feeds 页面、公开收藏更新、移除、空态和旋转草稿；
  同时渲染深浅主题、320dp 窄屏、1.6 倍字体和长标题 / 简介。
  回归场景包括后台编辑后重建、连续两页被过滤后的空态，以及单作品屏蔽 / AI 模糊时的封面。
- 本地联调：后端运行 `node scripts/dev-artlists.mjs`，设备执行 `adb reverse tcp:18087 tcp:18087`。
  此脚本只监听 loopback，使用内存数据库和测试身份；正式 APK 不使用这个身份或地址。
- 后端在 `pixshaft-api` 主服务，按部署拓扑发布至 `ssh pixshaft`。分享网页需要同步 Caddy 的 `/artlists/*` 路由。
  2026-11-12 已部署后端提交 `bd69c67`，并在保留线上访问防护配置的前提下补入分享页路由。
  线上健康检查、公开列表、未登录写入拒绝、分享页与字体资源已验证；Pixel 8 真实账号完成创建、加入插画、浏览、
  分享链接唤起和删除，临时画单已清理。最终 APK 已重新安装，详情顶栏的状态栏 Insets 经过真机截图验证。

## 本次工程审查记录（2026-11-12）

范围为本次画单前后端及其直接调用链；后端已有的 `scripts/backup/` 不在审查、提交或部署范围内。

| 位置 | 触发与影响 | 已落地修复 |
| --- | --- | --- |
| `pixshaft-api/src/artlists.js:53` | 多页作品作为封面时，列表重复携带全部原图分页数据，放大响应与解析成本 | 摘要查询移除原图分页字段，作品接口仍保留完整数据 |
| `ArtlistModels.kt:39` | 页面停止期间发生编辑，再重建页面时可能保留旧列表 | 刷新版本与 Feed 一起保存在 ViewModel |
| `ArtlistDetailFragment.kt:87` | 连续分页都被过滤时，首屏结果不能代表最终空态 | 浏览按钮和空态从整个 Feed 与分页结束状态派生 |
| `ArtlistViews.kt:29` | 单作品屏蔽或 AI 模糊命中时，封面仍可能露出图片 | 封面遵循屏蔽规则，列表与详情监听屏蔽变化 |
| `values-*/strings_artlist.xml` | 48 条新文案缺失翻译，触发 lint 错误 | 补齐现有 7 种非默认语言 |
| `ArtlistDetailFragment.kt:28` | 真机状态栏与详情标题栏重叠 | 详情使用包含统一 `toolbar_layout` 的专用布局，匹配 V3 Insets 接线 |

以上六项均已修复，无待定项。直接相关的 Pixiv 续页认证、查看者收藏状态恢复、Plaza 默认分页限制已检查，
原有行为保持；新画单的账号切换、权限、幂等、生命周期、分页和过滤边界由相关测试覆盖。

验证结果：后端相关测试 17 项、Android 合约与导航单测 5 项、safe 单测 16 项、Pixel 8 测试 3 项通过；
最终 `:app:assembleGithubDebug` 与 `:app:lintGithubDebug` 通过。后端全量测试另有 5 项既有失败
（借号池 4 项、翻译配额 1 项），已在未修改的基线上确认，未纳入本次修复。
后端 `bd69c67` 已推送 `main` 并部署；Android 画单实现及审查修复与本记录一并提交到 `classic`，
最终 APK 已安装到 Pixel 8。
