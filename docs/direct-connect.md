# Pixiv-Shaft 直连方案技术说明

## Cronet 是什么？（通俗版）

Cronet 就是 **Chrome 浏览器的网络引擎，单独拆出来给 APP 用**。

你用 Chrome 打开网页时，Chrome 内部有一套网络代码负责发请求、处理 HTTPS、支持 HTTP/2、HTTP/3 等协议。Google 把这套代码打包成了一个库叫 Cronet，任何 Android APP 都能引入它。

**为什么要用它？**

普通 Android APP 发网络请求用的是 OkHttp，底层走 **TCP + TLS**。GFW 从 2018 年封 Pixiv 起，就对握手包里出现 `*.pixiv.net` SNI 的 TCP+TLS 流量做全端口 RST 封锁。历史方案靠"去掉 SNI"绕开这一条路，但 TCP 路径的维护越来越脆弱。

Cronet 支持 **QUIC/HTTP3**，跑在 UDP 上。但需要澄清一点：**GFW 并不是"管不了 UDP"**。研究表明它已经能解析 gQUIC、解密 iQUIC Initial 包里的 SNI，并对特定域名做 UDP 层封锁（见 [USENIX Security 2025](https://gfw.report/publications/usenixsecurity25/zh/)）。我们之所以能走通 QUIC，是因为**`pixiv.net` 目前不在它的 QUIC SNI 黑名单里**。

换句话说：这不是一条"GFW 管不了的路"，而是一条"GFW 暂时还没来管的路"。一旦 `pixiv.net` 进 QUIC 黑名单，这条路径也会失效。

```
传统请求：APP → OkHttp → TCP → TLS（含 pixiv SNI）→ GFW TCP RST ❌
Cronet  ：APP → Cronet → UDP → QUIC（pixiv 暂未入 QUIC 黑名单）→ Cloudflare ✅
```

代价是 APP 体积会大几 MB（Chrome 的网络引擎打包进去了），换来当前可用的直连。

---

## 背景

先纠正几个常见的误解（也包括本项目早期 release note 里说错的地方）：

- **Pixiv API 在 Cloudflare 上不是最近的事**：`app-api.pixiv.net`、`oauth.secure.pixiv.net` 至少从 2025 年中起就由 Cloudflare CDN 托管（见 [dnshistory.org](https://dnshistory.org/dns-records/app-api.pixiv.net)）
- **Pixiv 旧 IP 没退役**：`www.pixiv.net` 通过 `210.140.*` 仍可访问
- **GFW 的 `*.pixiv.net` SNI 封锁不是新的**：从 2018 年封 Pixiv 开始就一直对包含该 SNI 的 TLS ClientHello 做全端口 TCP RST
- **域前置（SNI 操纵）"失效"的原因不是 SNI-RST**：恰恰相反，SNI 操纵/域前置本来就是用来对付 SNI-based 封锁的手段。它在 Cloudflare 上不可用的真正原因是：Cloudflare 边缘会校验 SNI 与 HTTP Host 一致，经典 domain fronting 已不再工作

历史直连方案主要靠两个前提：

1. **无 SNI TLS**：握手包里不出现 `pixiv.net`，就躲得开 SNI-based RST
2. **自定义 DNS**：绕开被污染的系统 DNS，把域名解析到可达 IP

2026 年 4 月前后，这套方案在 API 侧出现明显退化——部分账户开始遇到 403 或握手异常，而图片加载（`i.pximg.net`，仍走 Pixiv 自有基础设施）不受影响。具体是 Pixiv 自身风控、Cloudflare 规则变更、还是其他什么触发点，我们没有完全查清。与其继续在 TCP+TLS 路径上逐环排查，我们直接换了一条路：**把 API 请求整体迁到 HTTP/3**。

## 我们的方案：双通道架构

Pixiv-Shaft 采用 **QUIC + No-SNI TLS 双通道** 策略，分别处理 API 请求和图片加载：

```
┌─────────────────────────────────────────────────┐
│                  Pixiv-Shaft                     │
│                                                  │
│  ┌──────────────┐       ┌──────────────────┐    │
│  │   API 请求    │       │    图片加载       │    │
│  │  Retrofit     │       │    Glide          │    │
│  └──────┬───────┘       └────────┬─────────┘    │
│         │                        │               │
│  ┌──────▼───────┐       ┌────────▼─────────┐    │
│  │   Cronet      │       │   OkHttp          │    │
│  │   (QUIC/h3)   │       │   (No-SNI TLS)    │    │
│  │   UDP:443     │       │   TCP:443          │    │
│  └──────┬───────┘       └────────┬─────────┘    │
│         │                        │               │
└─────────┼────────────────────────┼───────────────┘
          │                        │
    ┌─────▼─────┐           ┌──────▼──────┐
    │ Cloudflare │           │ Pixiv 图片   │
    │ CDN (API)  │           │ 服务器       │
    │ 104.18.*   │           │ 210.140.*    │
    └───────────┘           └─────────────┘
```

### 通道一：API 请求 — Cronet QUIC

**问题**：防火墙在 TCP 层注入 RST 包阻断连接，传统 HTTPS 无法到达服务器。

**解法**：使用 Chromium 网络栈（Cronet）发起 HTTP/3 请求。HTTP/3 基于 QUIC 协议，运行在 UDP 之上。防火墙的 TCP RST 机制对 UDP 流量无效。

实现要点：
- **Cronet 引擎**：嵌入 Chromium 的 `cronet-embedded` 库，启用 QUIC 并配置 QUIC Hint 加速协议发现
- **DNS 绕过**：通过 `ExperimentalCronetEngine` 的 `HostResolverRules` 将 Pixiv 域名直接映射到 Cloudflare IP，绕过被污染的系统 DNS
- **OkHttp 桥接**：自定义 `CronetInterceptor` 实现 OkHttp 的 `Interceptor` 接口，将 Retrofit 请求透明地转发到 Cronet 执行，无需改动现有 API 层代码

```
请求流程：
Retrofit → OkHttp → CronetInterceptor → Cronet Engine → QUIC/UDP → Cloudflare
```

### 通道二：图片加载 — No-SNI TLS

**问题**：图片服务器（i.pximg.net）仍在 Pixiv 原有基础设施上，不支持 QUIC。

**解法**：图片服务器根据 IP 路由请求，不要求 TLS 握手中携带 SNI 扩展。去掉 SNI 后，防火墙无法从 TLS ClientHello 中识别目标域名，不会触发 RST。

实现要点：
- **RubySSLSocketFactory**：自定义 SSLSocketFactory，在创建 TLS 套接字时传入 `null` 作为主机名，使 Java TLS 不在 ClientHello 中包含 SNI 扩展
- **HttpDns**：自定义 DNS 解析器，将 `i.pximg.net` 映射到已知可用的图片服务器 IP（通过 DoH 动态解析或使用硬编码 fallback）
- **强制 HTTP/1.1**：禁用 HTTP/2 协议协商，避免多个图片请求复用同一连接——单连接被干扰时不会影响其他图片加载

```
请求流程：
Glide → OkHttp → RubySSLSocketFactory(无SNI) → HttpDns(自定义IP) → TLS/TCP → Pixiv 图片服务器
```

## 为什么不用其他方案

| 方案 | 问题 |
|------|------|
| 纯 DoH + 自定义 DNS | DNS 可以绕过，但 TCP+TLS 握手包里只要还带 pixiv SNI 就会被 RST |
| 修改 TLS 指纹 / Cipher Suite | 防火墙按 SNI 内容封锁，与 TLS 指纹无关 |
| 域前置 / SNI 操纵 | Cloudflare 边缘校验 SNI 与 HTTP Host 一致，经典 domain fronting 在 Cloudflare 上不可用 |
| ECH（加密 ClientHello）| Pixiv 的 Cloudflare 配置未启用 ECH |
| 备用端口（8443 等）| TCP 层 SNI 检测是全端口的，换端口无效 |
| 中继/代理服务器 | 依赖第三方服务，可用性不可控 |

## 关键依赖

- `org.chromium.net:cronet-embedded` — Chromium 网络栈，提供 QUIC/HTTP3
- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP 客户端
- 自定义 `CronetInterceptor` — OkHttp ↔ Cronet 桥接层
- 自定义 `RubySSLSocketFactory` — 无 SNI 的 TLS 连接
- 自定义 `HttpDns` — 防污染 DNS 解析，按域名分流 API/图片 IP
