# Pixiv-Shaft 图片源切换（issue #865）— 工程纪要

> **状态**：地基已落库 + 26 个 unit test 全过；**未接线**。回来续工先读本页。
>
> 关联：`docs/direct-connect.md`（与本任务有强互斥关系，见下文）。

## 背景

[issue #865](https://github.com/CeuiLiSA/Pixiv-Shaft/issues/865) 要求把图片域名抽象成可切换的 host：

- 默认 Pixiv 官方（`i.pximg.net` / `s.pximg.net`）
- Pixiv Cat（仅 `i.pixiv.cat`；`s.pixiv.cat` 不存在，镜像模式下 `s.pximg.net` 保持原样，见 [#1152](https://github.com/CeuiLiSA/Pixiv-Shaft/issues/1152)）
- 自定义反代（用户输入完整前缀，例如 `https://your.proxy[/optional/path]`）

动机：部分网络环境下走 No-SNI 直连 i.pximg.net 体验差（特别是 GIF），自建反代或 pixivcat 反而更快。`Settings.java` 里其实有个 `usePixivCat` 字段（line 126），但**没有任何消费者**——历史欠账，本任务一并清理。

## 已交付（地基）

| 文件 | 角色 |
|---|---|
| `app/src/main/java/ceui/lisa/http/ImageHostManager.kt` | 抽象本体 (~95 行)，`object` 单例 |
| `app/src/test/java/ceui/lisa/http/ImageHostManagerTest.kt` | 26 个边界用例 |

**设计要点：**

- `enum Mode { PIXIV, PIXIV_CAT, CUSTOM }` + 两个 `@Volatile` 状态：`mode` 和 `customHost`。**纯内存**，不动 Settings 持久化（接线时由 `Shaft.onCreate` 灌入）
- `rewrite(url): String` —— 唯一改写入口。识别 `i.pximg.net` / `s.pximg.net` 才动；其它 host、空串、解析失败的全部原样返回（防御性）
- `requiresStandardClient(): Boolean` —— 给 `Shaft.onCreate` 用的探针，非 PIXIV 模式必须强制走标准 OkHttp（绕开 directConnect 的 SSL/DNS/SNI bypass）
- **rewrite 在 load 时应用，不在 store 时**：data model 仍存原始 pximg URL；分享 / 复制 URL 走原始值（普世可访问，发给别人也能打开）

`ImageHostManager.kt` 头部 KDoc 写了完整 wiring 计划与默认假设，可独立读懂——本页是 KDoc 之外的扩展资料。

## 与 direct-connect 的互斥（必读）

`docs/direct-connect.md` 描述的"通道二：No-SNI TLS"对图片下载做了三件事，**三件都只对 `i.pximg.net` 有效**：

1. `HttpDns` 把 `i.pximg.net` 硬编码到 `210.140.139.134/133/131`
2. `RubySSLSocketFactory` 跳 SNI（pximg 服务器不要求 SNI；pixiv.cat / Cloudflare 反代强校验）
3. `TrustAllCertManager` + `hostnameVerifier((h, s) -> true)` 全放行（自定义反代时是安全风险）

切到 PIXIV_CAT / CUSTOM 时这套 bypass 必须**全部失活**。地基已经把这件事抽象成 `requiresStandardClient()` 探针；接线时 `Shaft.java:197` 的 gate 改成：

```java
if (sSettings.isDirectConnect() && !ImageHostManager.requiresStandardClient()) {
    // 原有 directConnect 的 SSL / DNS / 协议覆盖
}
```

## 接线 checklist（12 个文件）

| # | 文件 | 改法 |
|---|---|---|
| 1 | `app/src/main/java/ceui/lisa/utils/GlideUrlChild.java` | ctor 调 `ImageHostManager.rewrite(url)` —— **一处覆盖 ~85 个 .load() callsite** |
| 2 | `app/src/main/java/ceui/lisa/download/IllustDownload.java` | `getUrl` (line 336)、`getShowUrl` (line 376)、ugoira zip URL (line 157) 三处 return 包 rewrite |
| 3 | `app/src/main/java/ceui/lisa/activities/Shaft.java:197` | gate `isDirectConnect()` 块；启动时把 Settings 灌进 ImageHostManager |
| 4 | `app/src/main/java/ceui/lisa/utils/Settings.java` | 新字段 `imageHostMode: Int` + `customImageHost: String`；GSON 迁移 dead 字段 `usePixivCat` (line 126) |
| 5 | `app/src/main/java/ceui/pixiv/ui/settings/SettingsFragment.kt` 55-115 行 | 新 TabCellHolder（仿 line 102-112 `filter_invalid_bookmarks` 模式）+ 自定义 URL 二级页 |
| 6 | `app/src/main/java/ceui/lisa/fragments/FragmentSettings.java` | **先确认是否仍在 nav graph**；若是，同款 UI 入口 |
| 7 | `app/src/main/java/ceui/lisa/activities/OutWakeActivity.java:121` | host 检测 `contains("i.pximg.net")` → 加 `i.pixiv.cat` 与 `customHost`；建议抽 helper `isPixivImageHost(uri)` |
| 8 | `app/src/main/res/xml/network_security_config.xml` | 加 `pixiv.cat` 到 cleartext domain；自定义 host 文档要求 https |
| 9 | `app/src/main/java/ceui/pixiv/ui/comic/reader/ComicBookmarksSheet.kt:78-82` | `GlideUrl(previewUrl, LazyHeaders…)` → `GlideUrlChild(previewUrl)` —— bypass GlideUrlChild 的两处之一 |
| 10 | `app/src/main/java/ceui/pixiv/ui/comic/reader/ComicThumbsSheet.kt:77-81` | 同上 |
| 11 | `app/src/main/res/values{,-en,-ja,-ko,-ru,-tr,-zh-rTW}/strings.xml`（共 7 份） | 现有 `string_331`（"使用 PixivCat 代理"）已经有 6 个 locale；`values-tr` 缺；新增 `image_host_*` / `custom_host_*` 字串 |
| 12 | `app/src/main/AndroidManifest.xml` | **不需改**——只指向 network_security_config；google flavor manifest 也无 host 相关配置 |

## 不接线（避免下次重新研究）

**Dead constants（定义但 0 消费者）**：

- `Params.HOST_NAME = "i.pximg.net"` (line 78)
- `Params.IMAGE_UNKNOWN` (line 99)
- `Params.HEAD_UNKNOWN` (line 100)

**仅做 equality 检测、非 `.load()` —— 不改**：

- `GlideUtil.DEFAULT_HEAD_IMAGE`（用于识别"是不是默认头像"）
- `UserFollowingFragment.NO_PROFILE_IMG`（同上）

检测时拿到的是原始 API URL（pximg），不受 rewrite 影响。

**Dead UI 组件**（无外部 caller）：

- `MultiImageView.java:207`（TODO 注释自 2016 年 10 月）
- `NineAdapter.java:51`

**Share / 复制 URL（13 处，全部保留原始 pximg）** —— 设计上不接线：

```
ShareIllust.java               ClipBoardUtils.java
FragmentIllust.kt:364          FragmentWebView.java:416-418
PixivFragment.kt:502 / :524    ArtworkV3Fragment.kt:548
DoneListV3Fragment.kt:191-194  NovelReaderV3Fragment.kt:553/871/882
OcrResultFragment.kt:74
```

理由：分享给别人的 URL 应该是 pximg（普世可访问）。**如果未来用户反馈"分享出去的链接没法在 pixiv.cat 用户那边打开"再做选项，目前不做。**

**`HttpDns.java` / `RubySSLSocketFactory.java`** —— 仅在 directConnect+PIXIV 双 true 时被装到 OkHttpClient 上，gate 后自动失活，**本文件不需改**。

## 待决策（4 个）

| # | 决策 | 当前地基的默认 | 建议 |
|---|---|---|---|
| 1 | Custom URL 形态：完整前缀 vs host-only | 完整前缀（支持 `https://my.proxy/pixiv` 这种带子路径） | 保持完整前缀 |
| 2 | pixiv.cat 子域映射：i+s 都映射 vs 只映 i | i + s 都映射 | 保持都映射（覆盖头像/占位图） |
| 3 | directConnect 互斥 UX：自动关 vs 灰掉 | 不强制（探针只暴露 `requiresStandardClient`） | 自动关，更清晰 |
| 4 | 旧版 `FragmentSettings.java` 是否仍可达 | 未确认 | 接线前 `grep -rn FragmentSettings app/src/main` + 看 nav_graph |

## 下一步

按顺序：

1. 拍板上面 4 个决策点
2. 开接线 PR：先改文件 1-4（核心改写 + 配置 + 状态注入），跑现有单测
3. 文件 5-10（UI + 边角），手测 PIXIV / PIXIV_CAT / CUSTOM 三种 mode 各一轮
4. 文件 11（strings）+ 12（manifest 验证）
5. 改 `network_security_config.xml` 后跑一次完整 release build，确认 cleartext / TLS 行为正常
6. 接线落定后，KDoc 头部那段"NOT WIRED YET"提示从 `ImageHostManager.kt` 删掉

## 重新生成完整 callsite 表

```bash
# 所有 .load() callsite
grep -rn "\.load(" --include="*.kt" --include="*.java" --exclude-dir=build app/src/main

# GlideUrlChild ctor 调用方（Java new + Kotlin 直接构造）
grep -rn "GlideUrlChild(\|new GlideUrlChild" --include="*.kt" --include="*.java" --exclude-dir=build app/src/main

# 硬编码 pximg / pixiv.cat 字面量（排除 Params.java 大块 sample JSON 和 LofterAdapter 注释）
grep -rn "pximg\|pixiv\.cat" --include="*.kt" --include="*.java" --include="*.xml" --exclude-dir=build app/src/main \
  | grep -v "Params\.java:1[2-9][0-9]\|Params\.java:[2-3][0-9][0-9]\|LofterAdapter"
```

**精确分类**（截至本文写作时）：

- 类 A1（直接 `GlideUrlChild(...)`）：~24 处 → AUTO via ctor
- 类 A2（经 `GlideUtil.xxx()` 间接）：~52 处 → AUTO via ctor
- 类 B（绕过 GlideUrlChild）：4 处 → 必须显式改（2 ComicSheet + IllustDownload 3 个 return point）
- 类 C（dead / 非图片 / ViewModel.load 等）：~15 处 → 跳过
- 类 D（dead 常量）：5 处 → 跳过

合计 12 个改动文件、85+ 个调用点中只需直接动 6 个文件的代码，地基把杠杆放大到了这种程度。
