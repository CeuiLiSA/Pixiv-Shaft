# 借号会话库接入说明（`pixiv-premium-session`）

借号会话内核已抽离并开源发布为独立纯 JVM Kotlin 库 **[`SoxiaLiSA/pixiv-premium-session`](https://github.com/SoxiaLiSA/pixiv-premium-session)**（MIT 协议，JitPack 分发），在 `:app` 中通过 `implementation libs.pixivPremiumSession`（`com.github.SoxiaLiSA:pixiv-premium-session`）引入，与 `pixiv-login` 保持一致的依赖管理方式。

完整 API 与独立接入文档见上游仓库的 [`README.md`](https://github.com/SoxiaLiSA/pixiv-premium-session/blob/main/README.md)。

## 1. 核心职责划分

- **上游库 `com.github.SoxiaLiSA:pixiv-premium-session`**（包名 `ceui.pixiv.nana7mi.session`）：
  - 纯 JVM 11 字节码，运行时仅依赖 `kotlinx-coroutines-core-jvm` 与 `kotlin-stdlib`，零 Android / OkHttp / Retrofit / Gson / MMKV / Timber / Hilt 依赖。
  - 负责会话有限状态机（`Idle` / `CachedCursor` / `Active` / `Lost`）、主动/被动 OAuth 续期、`NonCancellable` 持久化前置校验、防取消早释的 FIFO 串行租约、有界预算重试、缓存游标 409 重放切换与会员归属合并策略（`MembershipPolicy`）。
- **本仓宿主适配层（`:app`）**：
  - 适配器：`app/src/main/java/ceui/pixiv/nana7mi/host/ShaftNana7miAdapters.kt`（桥接 `PixshaftApi`、`PixivLogin`、`AccountResponse`、`AccountOnlineReportOutbox`、Retrofit `HttpException`、`Nana7miQuotaNotice`、`Timber`、`Nana7miSearchCache`、`Nana7miSearchTelemetry`）。
  - 进程级组合根：`app/src/main/java/ceui/pixiv/nana7mi/host/ShaftNana7miSession.kt`（`@Singleton`，经 `ServicesProvider.nana7miSession` 注入）。
  - 消费方：`SearchIllustRepo` 与 `SearchNovelRepo` 复用 `executeFirstPage` / `executeNextPage`；`SessionManager.freshPremiumOf` 复用 `MembershipPolicy.attribute`；`NetworkFailureClassifier` 与 `Nana7miNetworkRetry` 复用 `TransportFailureClassifier` 与 `NetworkRetryExecutor`；`Nana7miQuotaNotice` 复用 `RateLimitInfo`。

## 2. 依赖位置

- 版本目录：`gradle/libs.versions.toml`（`pixivPremiumSession`）
- 宿主引入：`app/build.gradle`（`implementation libs.pixivPremiumSession`）
- 升级流程：在上游仓库 `SoxiaLiSA/pixiv-premium-session` 发新 tag → 等待 JitPack 构建就绪 → 更新 `gradle/libs.versions.toml` 中的版本号并跑宿主单测校验。
