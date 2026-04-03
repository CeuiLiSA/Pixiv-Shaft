# Pixiv-Shaft 直连方案技术说明

## 背景

2026 年 4 月起，Pixiv 将 API 服务（app-api.pixiv.net、oauth.secure.pixiv.net）迁移至 Cloudflare CDN。原有的直连方案因以下两点同时失效：

1. **旧服务器 IP 退役** — 210.140.139.155 等地址不再提供 API 服务
2. **SNI 封锁** — 防火墙对包含 `*.pixiv.net` 的 TLS ClientHello 实施 TCP RST（全端口）

常见的绕过手段（自定义 DNS + 关闭 SNI、修改 TLS 指纹、切换备用端口）均无法突破 TCP 层的封锁。

## 我们的方案：双通道架构

Pixiv-Shaft 采用 **QUIC + No-SNI TLS 双通道** 策略，分别处理 API 请求和图片加载：

```
+---------------------------------------------------+
|                   Pixiv-Shaft                      |
|                                                    |
|  +----------------+       +-------------------+    |
|  | API Request    |       | Image Loading     |    |
|  | Retrofit       |       | Glide             |    |
|  +-------+--------+       +---------+---------+    |
|          |                           |              |
|  +-------v--------+       +---------v---------+    |
|  | Cronet          |       | OkHttp            |    |
|  | (QUIC/h3)       |       | (No-SNI TLS)      |    |
|  | UDP:443          |       | TCP:443            |    |
|  +-------+--------+       +---------+---------+    |
|          |                           |              |
+----------+---------------------------+----------+
           |                           |
     +-----v-----+             +-------v-------+
     | Cloudflare |             | Pixiv Image   |
     | CDN (API)  |             | Server        |
     | 104.18.*   |             | 210.140.*     |
     +-----------+             +---------------+
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
| 纯 DoH + 自定义 DNS | DNS 可以绕过，但 TLS 握手仍然被 RST |
| 修改 TLS 指纹 / Cipher Suite | 防火墙按 SNI 内容封锁，与 TLS 指纹无关 |
| ECH（加密 ClientHello）| Pixiv 的 Cloudflare 配置未启用 ECH |
| 备用端口（8443 等）| 防火墙全端口监控 SNI |
| 中继/代理服务器 | 依赖第三方服务，可用性不可控 |

## 关键依赖

- `org.chromium.net:cronet-embedded` — Chromium 网络栈，提供 QUIC/HTTP3
- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP 客户端
- 自定义 `CronetInterceptor` — OkHttp ↔ Cronet 桥接层
- 自定义 `RubySSLSocketFactory` — 无 SNI 的 TLS 连接
- 自定义 `HttpDns` — 防污染 DNS 解析，按域名分流 API/图片 IP
