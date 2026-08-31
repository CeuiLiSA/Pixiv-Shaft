# Pixiv-Shaft 接入 shaft-api-v2 聊天 WebSocket 指南

服务端 @ HEAD `e1c7a01` 之后 + R1-R7 一组健壮性补丁(per-uid 连接 cap、
strict to_uid 校验、实时 display_name、Map iteration 快照、closeChatDb
原子性、文件 broker→router 改名)。
源码:`shaft-api-v2/src/chat/{db,router,auth,ws,routes,threadId}.js`。
客户端框架已落地于 `websocket/src/main/java/ceui/pixiv/websocket/`（独立 library module `:websocket`），聊天接入层在 `app/src/main/java/ceui/pixiv/chat/`。

本文件描述**服务端契约** —— 帧格式、鉴权、心跳、错误码、HTTP 配套 ——
以及怎么把这套契约对到现有 `WebSocketAuthProvider` / `RobustWebSocketClient` /
`ChatMessageStream` 抽象上。

---

## 0. 一句话总览

- 一个 app 对应**一条** WS,握手成功(收到 `hello`)后**自动接收**:
  - 所有 `room: "global"` 公共房广播 —— ⚠️ 服务端按握手带的 `v`(client versionCode)
    version-gate:`v` 缺失/过低的老客户端**不下发** global(见 §2.1)
  - 所有 `to_uid` 指向自己的 1v1 消息(无论自己有没有"打开"那个聊天)
- 客户端**不发 sub / unsub 帧**,服务端按 `uid` 做路由,连上即收
- 发消息:`{kind:"msg", to_uid:X, client_msg_id, text}`(1v1) 或
  `{kind:"msg", room:"global", client_msg_id, text}`(公共)
- 服务端用 `(uid, client_msg_id)` UNIQUE 去重写库,**客户端必须按 `client_msg_id` 去重渲染**

---

## 1. 连接生命周期

```
客户端                                                  服务端
  │                                                      │
  │  HTTP GET /api/v1/chat/ws?uid=&ts=&sig=  ────────→  │
  │                                                      │ verifyHandshake
  │  ←────────── 101 Switching Protocols ─────────────  │ router.register(uid, cb)
  │                                                      │
  │  ←──────── {"kind":"hello",uid,display_name,...}  ─ │  ★ 第一帧永远是 hello
  │                                                      │  (UI 此时切到 Connected)
  │                                                      │
  │  →  {"kind":"msg",to_uid:X|room:"global",         │
  │       client_msg_id,text,illust_id?}              │
  │  ←  {"kind":"msg",room,uid,display_name,            │ broker.deliverToUid 或 deliverToAll
  │       client_msg_id,text,ts,illust_id?}             │ enqueueMessage 异步落库
  │                                                      │
  │  ←  {"kind":"msg",...}  (来自任意他人 → 我)         │
  │                                                      │
  │  ←  {"kind":"err",code:"..."}  (协议/限频/校验错误)  │ ★ 不掉线
  │                                                      │
  │  ↕  RFC 6455 ping/pong (OkHttp pingInterval 自动)    │
  │                                                      │
  │ 四种 close:                                           │
  │ (A) ws.close(1000) 客户端主动 → 协商关 ──────────→   │
  │ (B) server raw.terminate() 抢断 ──────────────       │ ← 心跳/背压/shutdown
  │     onClose(1006, "")                                │
  │ (C) server ws.close(1008, "replaced") ──────────     │ ← 顶号(见 §1.1)
  │     onClose(1008, "replaced")                        │
  │ (D) TCP 异常 (NAT / 断网)                            │
  │     onFailure(IOException)                           │
```

**关键不变式**:

- ★ `hello` 永远是服务端发出的**第一帧**。OkHttp `onOpen` 触发不代表"接通",收到 hello 才算
- 服务端断连有两条路径,**区分对待**:
  - **`terminate()` → onClose(1006, "")** — 心跳/背压/shutdown/TCP 异常,**走重连流程**
  - **`close(1008, "replaced")` → onClose(1008, "replaced")** — 同 uid 顶号(见 §1.1),**不要立即重连**
- WS 是**无状态的会话** —— 重连之后服务端不会回放期间消息;客户端用 `/history?room=...&before=...` 主动补
- **不再有 sub/unsub 帧**。连上即收,跟微信 / Telegram / WhatsApp 一致

### 1.1 同 uid 并发连接上限(顶号语义)

服务端对每个 uid 最多保留 **5 条**并发 WS 连接(可通过 `CHAT_MAX_CONNS_PER_UID` 环境变量调)。
超出时**踢最老的一条**(Set 插入顺序 = LRU),被踢的连接收到:

```
onClose(code=1008, reason="replaced")
```

设计理由:
- **5 而不是 1**:允许"重连临界态"(旧连接 onClose 还没传到 router,新连接已建好)有 1-2 个并存窗口;也给未来"手机 + 桌面 + 网页"留 3 个余量
- **踢最老不踢最新**:符合用户预期"我刚才操作的设备应该是活的"
- **client 端识别 1008 "replaced"**:UI 提示"账号在其它设备登录,已断开此处会话",**不要立即重连** — 否则两端会无限拍下对方变 thrashing。建议让用户显式点"切换回来"再重新连

`shaft_chat_conns_evicted_total` 指标记录踢老次数,可观测异常多端登录行为。

---

## 2. 鉴权(HMAC over `uid|ts`)

### 2.1 签名规则

```
握手 URL:
  ws://host:8080/api/v1/chat/ws?uid=<long>&ts=<unix_ms>&sig=<hmac_hex>&v=<versionCode>

sig = HMAC_SHA256_HEX(
  key  = native SHAFT_EVENTS_HMAC UTF-8 bytes,               // ASCII 字节,不是 hex 解码
  data = "${uid}|${ts}".toByteArray(UTF_8),                 // 注意:v 不进签名
)
```

密钥不进入 `BuildConfig` 或 JVM 字符串常量。构建任务从 Gradle property / 环境变量读取
`SHAFT_EVENTS_HMAC`,每次用新的随机掩码生成仅位于 `app/build/` 的 C++ header，最终由
`libshaft_secrets.so` 在一次签名期间恢复、使用并清零。客户端仍是不可信环境；若协议允许
升级，首选服务端校验设备完整性后下发短期凭证，而不是在 APK 中长期保存共享密钥。

四个坑(都踩过):

1. **key 是 ASCII 字节**(密钥 hex 字符串本身的 UTF-8 编码),不是 hex 解码后的 32 字节
2. **`|` 是 ASCII 0x7C**
3. **ts 用 `String(System.currentTimeMillis())`** —— 严格 13 位十进制,**不要带 `.0` / 科学计数法 / 前导空格**。服务端用 query 收到的 *字符串原样* 进 HMAC,不会先 Number() 再 toString()
4. 输出 hex **小写**

服务端 `ts` 正则:`/^[1-9][0-9]{12,13}$/`,与当前时间差 ≤ 60s。

#### `v`(client versionCode)—— 公共房按版本下发的服务端契约

`v` 是客户端 app 的 `BuildConfig.VERSION_CODE`,作为**明文 query 挂在握手 URL**,
**不进 sig**。这样保证向后兼容:现网服务端只验 `uid|ts` 的 sig、忽略未知 query,
所以新客户端先发 `v`、服务端后上线读 `v`,中间不会把新客户端锁在门外。

**服务端需要实现的 gate(broker/router 的 `deliverToAll`/global fan-out 处):**

- 仅向 `v >= GLOBAL_MIN_VERSION` 的连接下发 `room:"global"` 广播;
- `v` 缺失或 `< GLOBAL_MIN_VERSION`(= 现存所有已发布版本,它们根本不带 `v`)→ **不发 global**;
- 1v1(`to_uid`)下发不受影响。

`GLOBAL_MIN_VERSION` 取**第一个带"默认关 + 开关 gate"** 的客户端 versionCode
(即 40740 之后的那个新版本号)。这是唯一能拦住**已冻结的老客户端**"无脑弹公共聊天
push banner"的手段 —— 老客户端收到就弹,改不动,只能让它收不到。建议把
`GLOBAL_MIN_VERSION` 做成服务端可热改的配置,不必每次重新部署就能抬高门槛。

> `v` 是 feature-gate 提示、不是凭证,可被改包伪造;但老客户端是冻结的、根本不发 `v`,
> 不在威胁模型内,因此不进签名是可接受的取舍。

### 2.2 握手失败响应

| HTTP | body | 含义 / 处理 |
|------|------|---|
| 101 | switching protocols | 握手成功,等 `hello` 帧 |
| 401 | `{"error":"bad_uid"}` | uid 不是非零起首 decimal,或不在 uint64 内 |
| 401 | `{"error":"bad_ts"}` | ts 格式不符 |
| 401 | `{"error":"ts_skew"}` | 客户端时钟漂移 > 60s,**重试不解决**,提示用户校时 |
| 401 | `{"error":"bad_sig"}` | 签名错(密钥不一致 / ts canonicalization 不一致) |
| 429 | `{"error":"rate_limited","scope":"ip","retryAfterSeconds":N}` | 同 IP > 200 次/分,退避 N 秒,**禁止立即重试成环** |
| 503 | `{"error":"shutting_down"}` | 服务端 graceful shutdown 中,5–10s 后重连 |

> ⚠️ 认证**只看 query string**,不读 header / cookie。OkHttp 写 WS 时容易顺手 `addHeader("Authorization", ...)` —— 服务端会忽略。

### 2.3 对接 `WebSocketAuthProvider`

```kotlin
class ShaftHmacAuthProvider(
    private val baseHttpUrl: String,            // BuildConfig.SHAFT_EVENTS_BASE_URL
    private val uidProvider: () -> Long,        // 当前登录用户的 pixiv uid
    private val appVersionCode: Int,            // BuildConfig.VERSION_CODE
) : WebSocketAuthProvider {

    override fun nextRequest(): Request {
        val uid = uidProvider()
        require(uid > 0L) { "uid not initialised (not logged in?)" }
        val ts  = System.currentTimeMillis().toString()
        val sig = ShaftHmac.signHex("$uid|$ts")  // 只签 uid|ts；密钥不离开 native 层
        val url = deriveWsBase(baseHttpUrl) +
            "/api/v1/chat/ws?uid=$uid&ts=$ts&sig=$sig&v=$appVersionCode"
        return Request.Builder().url(url).build()
    }
}

private fun deriveWsBase(httpBase: String): String =
    httpBase.removeSuffix("/")
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
```

所有调用统一走 `app/src/main/java/ceui/pixiv/shaftapi/ShaftHmac.kt`，签名实现在
`libshaft_secrets.so`；不要另写 JVM HMAC，也不要把密钥重新暴露给 managed code。

---

## 3. WS 帧规约

### 3.1 客户端 → 服务端

#### `msg` — 发消息(唯一的"写"操作)

公共房:
```json
{"kind":"msg","room":"global","client_msg_id":"<UUID>","text":"你好","illust_id":12345}
```

1v1 房:
```json
{"kind":"msg","to_uid":67890,"client_msg_id":"<UUID>","text":"你好","illust_id":12345}
```

字段约束:

| 字段 | 必填 | 约束 |
|---|---|---|
| `kind` | ✓ | `"msg"` |
| `to_uid` 或 `room` | ✓ | 二选一。`room` 只接受 `"global"`,数字 room id 会被拒(见 ACL) |
| `to_uid` 类型 | — | **严格**:JSON 数字字面量(整数)或 canonical decimal 字符串(`/^[1-9][0-9]{0,18}$/`)。**不接受** `true` / 数组 / `" 200 "`(带空格) / `1.5` 等需要隐式转换的形态,服务端不做 `Number()` coercion |
| `client_msg_id` | ✓ | `/^[A-Za-z0-9_-]{8,64}$/`,客户端生成的 UUID / ULID / nanoid |
| `text` | ✓ | 1–2048 UTF-16 单元(emoji 算 2 单元),尾部 `\r\n` 服务端 strip |
| `illust_id` | optional | 正整数 < 10^15,关联一个 pixiv 作品 |
| `reply_to` | optional | `{"uid": <long>, "client_msg_id": "<id>"}` — 回复(引用)**本房间**里的另一条消息。`uid` 校验同 `to_uid`(严格数字 / canonical 十进制串),`client_msg_id` 同上方规则。服务端按 `(uid, client_msg_id)` 找到原消息并钉死 `room` 一致:找不到 / 跨房 → `err.reply_target_not_found`;形态错 → `err.bad_reply_to`。客户端只对 **Delivered 且有 client_msg_id** 的行提供「回复」 |

回复示例:
```json
{"kind":"msg","room":"global","client_msg_id":"<UUID>","text":"同意","reply_to":{"uid":12345,"client_msg_id":"<原消息 UUID>"}}
```

整帧 ≤ **4096 UTF-16 单元**(ASCII ≈ 4 KB / 中文 ≈ 12 KB UTF-8)。超就 `err.frame_too_large`。

#### `ping` — 应用层心跳(可选)

```json
{"kind":"ping"}
```

服务端回 `{"kind":"pong","server_ts":...}`。**正常用 OkHttp `pingInterval` 即可,这条是给某些发不了 RFC ping 控制帧的栈兜底**。任何入站(包括这条)都续命心跳计时。

### 3.2 服务端 → 客户端

#### `hello` — 握手成功的第一帧

```json
{"kind":"hello","uid":12345,"display_name":"匿名_12345","server_ts":1778740123456}
```

- `display_name`:当前展示名。用户没设过自定义就是 `匿名_<uid>`
- 客户端拿到 hello 才把 UI 切到 Connected;`onOpen` 早于 hello,不算"接通"
- **改名后的下一帧广播会立刻是新名** — server 每条 msg 实时查 display_name(see R7 in commit log)。`hello` 这一帧本身是连接刚建立时发的,不受这个影响

#### `msg` — 消息广播(自己发的也回来一份)

```json
{
  "kind": "msg",
  "room": "global",             // 或 1v1 thread id(数字字符串)
  "uid": 12345,                 // 真实 sender uid(server 改写,不信客户端字段)
  "display_name": "lisa",       // sender 当前 display_name
  "client_msg_id": "<UUID>",    // ★ 客户端去重必读字段
  "text": "你好",
  "illust_id": 12345,           // optional
  "ts": 1778740123502,
  "reply_to": {                 // optional — 这条是回复时才有
    "uid": 12345,               //   被引用消息的作者 uid
    "client_msg_id": "<UUID>",  //   被引用消息的 client_msg_id(= 客户端本地 localKey,可直接跳转)
    "display_name": "lisa",     //   作者**当前**展示名(实时查,改名即生效)
    "text": "原消息…"            //   原文前 200 UTF-16 单元;null = 原消息已过保留期被清理
  }
}
```

**关键**:
- **`reply_to` 是服务端下发的快照**:接收端不需要本地有那条原消息就能渲染引用块(新装 / 没翻到那么远的历史都一样)。`text:null` 渲染成「原消息已不可用」。点击引用块 → 按 `client_msg_id` 在本地列表里找原消息跳转,找不到就提示不在已加载记录里
- **没有 server-issued `id`** —— DB autoincrement id 异步分配,广播时还没有。客户端用 `client_msg_id` 做对账锚点;真要拿 `id` 走 `/history`
- **自己发的消息也走这条回来** —— UI 应当以这条为渲染源("回声 = 隐式 ACK"),不要双渲染 optimistic + 回声
- **`room` 字段必看**:单 WS 多会话场景下,客户端按 `room` 分桶到对应聊天窗口

#### `err` — 协议 / 限频 / 校验错误

```json
{"kind":"err","code":"bad_text_length","client_msg_id":"abc-123"}
```

- 服务端发完 **不掉线**,**也不进半死状态** —— 下一帧仍按正常流程处理
- **当入站帧带 `client_msg_id` 时,err 一定回显回去**(包括 cmid 本身格式错的 `bad_client_msg_id` 也照原样回显)。客户端用它把这条 err 精准锚定到 optimistic UI 里那条"发送中"的消息,把它标"失败"。多条消息在飞时不会错把别条标失败
- 灵感:weaver `ChatServerAckMessage.SeqId` 永远 copy 客户端原值 — "失败也得指明是哪条"
- **可选 `message` 字段**:策略类错误(目前 `global_send_disabled`)会附带一段可直接展示的中文文案,例如 `{"kind":"err","code":"global_send_disabled","client_msg_id":"abc-123","message":"公共聊天室当前已关闭发言"}`。客户端 toast **优先用 `message`**,缺省再走本地 code→文案兜底映射 —— **永远不要把机器码直接 toast 给用户**。`message` 让服务端改文案不必发客户端版本。

完整 err code 表:

| code | 触发条件 | 客户端建议处理 |
|---|---|---|
| `frame_too_large` | 帧 > 4096 UTF-16 单元 | 客户端先卡 4096 别让用户发出 |
| `bad_json` | 不是合法 JSON | 客户端 bug,记日志 |
| `bad_envelope` | JSON 不是对象 / 是数组 | 同上 |
| `unknown_kind` | `kind` 不在 `msg`/`ping` 内 | 同上 |
| `bad_text` | `text` 不是字符串 | 同上 |
| `bad_text_length` | `text` 空 / 超 2048 UTF-16 单元 | 客户端先卡 2048 |
| `bad_illust_id` | `illust_id` 不是正整数 / 超 10^15 | 同上 |
| `bad_client_msg_id` | client_msg_id 不符 8-64 [A-Za-z0-9_-] | 改 UUID v4 或 nanoid 即可 |
| `bad_room` | room 不是 `'global'` 也不是合法 numeric | 同上 |
| `bad_to_uid` | to_uid 不是正整数 / 越界 | 同上 |
| `room_required` | 既没 to_uid 也没 room | 同上 |
| `room_forbidden` | 直接传了 numeric room id(不允许,防偷听他人 1v1) | 改用 `to_uid` |
| `self_chat_not_allowed` | to_uid === self_uid | 不让用户给自己发(MVP 限制) |
| `rate_limited` | 每连接 5 条/5s 令牌桶溢出 | 1s 内 disable 发送按钮 |
| `global_send_disabled` | 管理员后台关闭了公共聊天室发言(只拦 `room:"global"`,1v1 不受影响) | toast `message` 字段文案;读历史仍可用,只是发不出去 |
| `bad_reply_to` | `reply_to` 不是对象 / `uid` 或 `client_msg_id` 形态不合法 | 客户端 bug,记日志 |
| `reply_target_not_found` | `reply_to` 指向的消息不存在,或不在目标房间(跨房引用一律按不存在处理,不泄露别房是否有这条) | 带 `message`(「原消息已不存在,无法回复」);标该行 Failed + toast。通常是回复了一条已过 30 天保留期的本地缓存消息 |

#### `pong` — 响应客户端应用层 `ping`

```json
{"kind":"pong","server_ts":1778740123502}
```

---

## 4. 幂等 / 客户端必须去重

这是这套协议**最容易踩坑**的地方,单独成节强调。

### 4.1 服务端的去重

服务端 `chat_messages` 表上有:
```sql
CREATE UNIQUE INDEX idx_chat_msg_idempotency
  ON chat_messages (uid, client_msg_id)
  WHERE client_msg_id IS NOT NULL;
```

`enqueueMessage` 走 `INSERT OR IGNORE` —— 同 `(uid, client_msg_id)` 第二次进 DB 被静默丢弃。**写库幂等**。

### 4.2 但广播不幂等!

`broker.publish` / `router.deliverToUid` **同步立即扇出**,在 DB 写之前。所以:

```
客户端发 msg A(client_msg_id=X)            ─→ server 立即 deliver 给 [sender, peer]
   ↓ 网络抖动 / 客户端重试
客户端发 msg A(client_msg_id=X 同一个)      ─→ server 又一次 deliver 给 [sender, peer]
                                                  ↓ DB flush
                                                  ↓ 第二次 INSERT 被 INDEX 吃掉,DB 只有 1 行
但 sender 和 peer 各收到了 2 帧 msg(同 client_msg_id)
```

**结论:客户端必须按 `client_msg_id` 在 UI / 本地 store 层去重渲染。**

### 4.3 客户端怎么做

`RoomChatMessageStore` 写入时(无论来自 WS 广播还是 /history):

```kotlin
// 用 client_msg_id 作为本地唯一 key(没有就用 server id)
val localKey = entity.clientMsgId ?: "server:${entity.id}"

dao.upsertByKey(localKey, entity)  // UPSERT,不 INSERT
```

发送侧:

```kotlin
fun sendMsg(text: String, toUid: Long?) {
    val clientMsgId = UUID.randomUUID().toString()
    // 1) 立刻 optimistic UI(state=Sending)
    upsertLocal(ChatMessageEntity(
        clientMsgId = clientMsgId,
        uid = selfUid, text = text, ts = now,
        state = Sending, ...
    ))
    // 2) 发 WS 帧
    ws.send(buildMsgFrame(clientMsgId, text, toUid))
    // 3) 网络出错时重试 — 同样的 clientMsgId
}

// 收到 WS 广播 msg 帧时
fun onIncomingMsg(frame: ChatFrame.Msg) {
    upsertLocal(ChatMessageEntity(
        clientMsgId = frame.clientMsgId,  // 跟 step 1 同一个 → UPSERT 触发,state 改 Delivered
        ...
    ))
}
```

UPSERT 走 client_msg_id 主键(或唯一索引),重复广播只是覆盖现有行,UI 不会出双份。

### 4.4 client_msg_id 生成建议

| 选项 | 长度 | 优点 |
|---|---|---|
| UUID v4 (`UUID.randomUUID().toString()`) | 36 | Java 内置,无依赖 |
| ULID | 26 | 时间排序友好(可单机内单调) |
| nanoid (`NanoId.generate()`) | 21 | 最短 |

任一即可,服务端只看 8–64 字符 `[A-Za-z0-9_-]`(注意 UUID 的 `-` 是合法,直接传)。

---

## 5. ACL & 路由规则

服务端的 `resolveTarget(env, selfUid)`(在 `chat/ws.js`):

| 客户端入参 | 服务端行为 |
|---|---|
| `room: "global"` | mode=global,`deliverToAll`(所有在线用户) |
| `to_uid: X` (X ≠ self) | mode=dm,room = `pairRoomId(self, X)`,`deliverToUid(self) + deliverToUid(X)`。**peer 离线也照常 enqueue,等他下次 /history 补** |
| `to_uid: self` | `err.code = "self_chat_not_allowed"` |
| `room: "<数字>"` (1v1 thread id) | `err.code = "room_forbidden"` — 强制走 to_uid 路径,见 §5.1 |
| 都没传 | `err.code = "room_required"` |

### 5.1 为什么禁止 sub/send 数字 room id

`pairRoomId(a, b)` 用 weaver `ReverseXOR(a, b)` 派生:

```
threadId = ReverseXOR(min(a,b), max(a,b))
         = uint64 decimal string,例 "1315111791659470611"
```

ReverseXOR **是单向函数** —— 从 threadId 反推不出 (a, b) 是哪两个 uid。
所以:如果允许 `sub room: "1315111791659470611"`,server 无从校验 sender
是不是这个 1v1 房的合法成员之一。**任何人猜到 (任意 A, 任意 B) 的 thread id
就能偷听**。

强制 `to_uid` 路径绕开这个问题:server 用 `pairRoomId(authed_self_uid, env.to_uid)`
派生 room,sender 永远是 (self, X) 中的 self,ACL 天然成立。

⚠️ **HTTP `/chat/history?room=<numeric>` 当前没此 ACL** —— 任何人知道两个 uid
就能查到他们的 1v1 历史。MVP 已知漏,见 §10。

---

## 6. 心跳与活性

```
握手成功 → lastInboundTs := now,启动 30s interval(首次 +30s 触发,不是 t=0)

每次 interval 触发(t=30s, 60s, ...):
  if (now - lastInboundTs > 60s) → raw.terminate() 抢断,客户端见 onClose(1006)
  else → server 主动发 RFC ping

收到 *任何* 入站(RFC pong / msg / ping 帧 / 任意 text frame):
  lastInboundTs := now
```

含义:
- **只要客户端在聊天就在续命** —— 不需要客户端额外发心跳
- **空闲连接靠 RFC ping/pong 自动续命** —— OkHttp ws 库自动 pong 响应即可

### 6.1 OkHttp 推荐配置

```kotlin
val ws: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)    // WS 永不超时,靠 ping 探活
    .writeTimeout(10, TimeUnit.SECONDS)
    .pingInterval(30, TimeUnit.SECONDS)        // 跟服务端同 30s,任意一边 NAT 先 idle 时另一边的 ping 撞醒它
    .retryOnConnectionFailure(true)
    .build()
```

### 6.2 不要这么做

- ❌ 用应用层 `{kind:"ping"}` 当心跳 —— OkHttp 已经做 RFC ping 了
- ❌ 用 `readTimeout` 检测断连 —— WS 长连接空闲就是没数据,设了反而误杀
- ❌ 觉得"没流量就该断连" —— RFC ping 走的是控制帧,看不见

---

## 7. 重连策略

服务端没有"等你回来"语义,重连后:
1. **不回放断连期错过的消息** —— 用 `/chat/history?room=...&before=<最后看到的 id>` 主动补
2. **未发出的本地消息** 不要在 reconnect 后自动 flush —— 用户已看到"发送中"状态,重发可能产生重复;**靠 client_msg_id 走重发是安全的(server 幂等),但 UX 上建议用户确认重发**

### 7.1 退避表

| 第 N 次重连 | 基础等待 | 实际(±20% jitter) |
|---|---|---|
| 1 | 1s | 0.8–1.2s |
| 2 | 2s | 1.6–2.4s |
| 3 | 4s | 3.2–4.8s |
| 4 | 8s | 6.4–9.6s |
| 5+ | 16–30s | jitter ±20% |

收到 `hello` 帧 → 视为成功 → 退避计数清零。

### 7.2 立即重试场景

- `NetworkMonitor` 报 OFFLINE → ONLINE:清退避立即重连
- App 从后台回前台 + state=Disconnected:立即重连
- 收到 `503 shutting_down`:固定等 5–10s 第一次重连

### 7.3 错误处理矩阵

| 场景 | 客户端 |
|---|---|
| 握手 401 `bad_uid` | UID 未初始化(没登录),retry 一次后 `FatalAuth` |
| 握手 401 `bad_ts` / `bad_sig` | 客户端 bug 或密钥配错,`FatalAuth` |
| 握手 401 `ts_skew` | 用户时钟错乱,UI 提示校时 |
| 握手 429 | 按 Retry-After header 退避,否则指数退避 |
| 握手 503 | 5–10s 后第一次重连 |
| 收到 `err.frame_too_large` / `bad_*` | 客户端 bug,记日志,UI 提示"消息发送失败" |
| 收到 `err.rate_limited` | 1s 内 disable 发送按钮 |
| `onClose(1000, ...)` | 自己关的,**不重连** |
| `onClose(1006, "")` | server terminate 或 TCP 异常 —— **走重连流程** |
| `onClose(1008, "replaced")` | 同 uid 顶号(见 §1.1)—— **不要立即重连**,UI 提示后等用户操作 |
| `onFailure(IOException)` | 走 `ReconnectStrategy` |

---

## 8. HTTP 配套

### 8.1 `GET /api/v1/chat/history?room=<id>&limit=50&before=<id>`

拉历史。

Response:
```json
{
  "room": "global",
  "limit": 50,
  "items": [
    {
      "id": 1,                          // server autoincrement, 历史里有
      "uid": 12345,
      "client_msg_id": "<UUID>|null",   // server 直插的没 token
      "display_name": "lisa",
      "text": "...",
      "illust_id": 123,                 // optional
      "ts": 1778740123456,
      "reply_to": {                     // optional — 仅回复消息带;形态同 WS msg 帧
        "uid": 12345,
        "client_msg_id": "<UUID>",
        "display_name": "lisa",         // 作者当前展示名
        "text": "原消息…"                // ≤200 单元;null = 原消息已被清理
      }
    }
  ]
}
```

- `items` 按 **ts 升序**(旧→新)
- `reply_to` 由服务端读时自连接 `(uid, client_msg_id)` 填充(不是写入时的快照),所以被引用作者改名立即生效;原消息被保留期清理后 `text` 变 `null`,`display_name` 仍按 uid 派生。没引用的消息**省略**这个 key(不发 `null`)
- 下一页 cursor:`before = items[0].id`
- 空 = 到顶
- `limit` 默认 50,上限 200
- ⚠️ 1v1 历史(`room=<numeric>`)**目前无 ACL**,见 §10

> 对接 `ChatHistorySource` / `MessagePage`:items 直接映射到 `MessagePage.items`,
> `items[0].id` 作下一页 cursor。

### 8.2 `POST /api/v1/chat/profile` — 改昵称

Body:
```json
{"uid":12345,"ts":1778740123456,"display_name":"新名字"}
```
Header: `X-Shaft-Sign: HMAC(secret, "${uid}|${ts}") hex`

约束:
- `display_name` 1–32 UTF-16 单元 / UTF-8 字节 ≤ 96
- 禁 ASCII 控制字符(`\x00-\x1f` / `\x7f`)
- 服务端 trim 首尾空白后校验

Response: `{"ok":true,"display_name":"新名字"}`

错误码:`bad_display_name_length` / `bad_display_name_chars` / `bad_display_name_bytes` /
`bad_uid` / `bad_ts` / `ts_skew` / `bad_sig`

**改名后立刻生效**:server 每条 msg 实时查 display_name 而非闭包缓存。
你下一条发出的消息,自己的回声 + 别人收到的广播 + /history 的 LEFT JOIN
都是新名,无需 reconnect。`hello` 帧不变(那只在连接建立时发一次)。

### 8.3 `GET /api/v1/chat/profile?uid=<long>`

查任意 uid 当前展示名。给历史回放遇到陌生 uid 时补名。

Response: `{"uid":12345,"display_name":"..."}`

### 8.4 `GET /api/v1/chat/stats`

```json
{
  "room": "global",
  "online": 142,              // 不同 uid 在线数
  "total_connections": 145,   // 物理 WS 数(multi-device 时 ≥ online)
  "total_messages": 893       // global 房 chat_messages 总行数
}
```

---

## 9. 客户端架构对接

### 9.1 总体映射

| 服务端契约 | 对应客户端层 |
|---|---|
| 握手 sig 校验失败 | `WebSocketAuthProvider` 抛 `AuthFailedException`,上层 `FailureContext = AUTH_FATAL`,停止重连 |
| `hello` 帧 | `WebSocketState.Connected(displayName)` 触发条件 |
| `msg` 帧广播 | `ChatMessageStream.messages: Flow<ChatMessage>` 解码后 emit |
| `err` 帧 | `WebSocketEvent.ProtocolError(code)`,UI 选择是否 toast |
| RFC pong | `RobustWebSocketClient` 内部用,不上抛 |
| `GET /chat/history` | `ChatHistorySource.load(cursor) → MessagePage` |
| `POST /chat/profile` | 独立 use case 或 VM 函数 |
| `GET /chat/stats` | Debug 抽屉,主链路不依赖 |

### 9.2 关键去重契约

**`ChatMessageEntity` 主键 = `client_msg_id`**(没有则 fallback 到 `"server:${serverId}"`)。
所有写入(WS 广播 / /history 拉取)都走 UPSERT。

```kotlin
@Entity(primaryKeys = ["local_key"])
data class ChatMessageEntity(
    @ColumnInfo(name="local_key") val localKey: String,  // client_msg_id 或 "server:$id"
    @ColumnInfo(name="server_id") val serverId: Long?,   // /history 返回的有,WS 广播没有
    @ColumnInfo(name="client_msg_id") val clientMsgId: String?,
    val uid: Long,
    val room: String,
    val displayName: String,
    val text: String,
    val illustId: Long?,
    val ts: Long,
    val state: SendState,  // Sending | Delivered | Failed
)

// 入站收到 msg 帧
fun onIncomingMsg(frame: ChatFrame.Msg) {
    val localKey = frame.clientMsgId
    dao.upsert(ChatMessageEntity(
        localKey   = localKey,
        serverId   = null,             // WS 广播不带
        clientMsgId = frame.clientMsgId,
        uid = frame.uid, room = frame.room,
        displayName = frame.displayName, text = frame.text,
        illustId = frame.illustId, ts = frame.ts,
        state = SendState.Delivered,
    ))
}
```

UPSERT 的好处:
- 客户端 optimistic 写一行 `(localKey=X, state=Sending)`
- WS 回声到达,UPSERT 用同样 localKey 覆盖,state 翻成 Delivered
- 服务端重发广播(网络抖动),再次 UPSERT 同 localKey,内容相同,UI 不抖

### 9.3 OkHttp 实例独立

聊天用单独 OkHttp 实例,**不**复用 events 的:
```kotlin
val chatWs: OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(30, TimeUnit.SECONDS)
    .build()
```
- `readTimeout=0` —— WS 不靠 read timeout 探活
- `pingInterval=30s` —— 跟服务端心跳节奏对齐

---

## 10. 已知限制 & 路线

| 项 | 现状 | 解决方向 |
|---|---|---|
| **1v1 /history 无 ACL** | 任何人知道两个 uid 就能查私聊历史 | 加 `?with_uid=X` 走 HMAC sig,server 派生 room — TODO |
| 没有 server-issued message id 在广播帧 | 仅 client_msg_id;client 拿 `id` 要 /history | 改同步 INSERT 取 lastInsertRowid 后 publish,延迟 +1ms |
| ~~改名要 reconnect 才生效~~ | ✅ **已修(R7)**:每条广播实时查 display_name,改名立即生效 | — |
| 单 fork event loop 在 5k+ 全局广播时打满 | `deliverToAll` O(N) | uWebSockets.js 或 cluster + Redis pub/sub |
| in-memory router 多机不通 | 单机部署 | broker.js 一文件换 Redis 实现 |
| 明文 ws:// | 复用 events 的 cleartext 配置 | Caddy + LE 上 wss(NAT 也能签证书,DNS-01) |
| 没有"已读"语义 | / | 客户端本地存 last_read_ts,server 不参与 |
| 没有撤回/编辑 | / | 加 WS `del`/`edit` 帧 + DB delete/update |
| 没有"双方同意才能开 1v1" | 任何 uid 可单方发 1v1 给任何 peer | 加 chat_request 流程(类似 weaver) |
| 同 uid 多设备 | router 支持(broadcast 给每个连接) | UX 上需要"另一设备登录"提示 |
| 偶发广播重复 → client 必须按 client_msg_id 去重 | 跨连接 retry 时 | 服务端加 per-uid 60s TTL Set 严格去重(成本/收益看流量) |

---

## 11. 调试

### 11.1 服务端实时观测

SSH alias `shaft-v2`:

```bash
# 在线人数 / 总消息数
curl http://<host>:8080/api/v1/chat/stats

# 看具体 uid 当前展示名
curl 'http://<host>:8080/api/v1/chat/profile?uid=12345'

# 拉最近 5 条(确认收到)
curl 'http://<host>:8080/api/v1/chat/history?limit=5'

# /admin 控制台聊天 tab
open 'http://<host>:8080/admin#chat'  # basic auth

# 服务端 chat 日志
ssh shaft-v2 'pm2 logs shaft-api-v2 --lines 200 | grep chat'
```

服务端 INFO / WARN 关键字:
- `chat ws open` + `uid` + `online`(当前不同 uid 在线数)
- `chat ws close`
- `chat ws evicted — exceeded per-uid conn cap`(顶号触发)
- `chat ws heartbeat lost — terminating`(60s 无入站)
- `chat ws backpressure exceeded — terminating`(buffered > 256KB)
- `chat batch insert failed`(基本不该发生)
- `router deliver to uid failed`(单订阅者抛错,扇出仍正常)
- `router evict onEvict threw`(顶号回调本身抛错,极少)

### 11.2 Android 端

```bash
adb logcat -s ChatWS:V WebSocket:V ChatStream:V
```

OkHttp 的 WS 握手 HTTP 来回不进 `HttpLoggingInterceptor`(升级后接管 socket),
但**失败的握手(401/429)走普通 HTTP 路径**,interceptor 能看 body —— 调认证时
特别有用。

### 11.3 本地起 server 自测

```bash
cd shaft-api-v2
EVENTS_HMAC_SECRET=$(cat /etc/shaft-api-v2/events-hmac-secret) \
PORT=8080 HOST=127.0.0.1 NODE_ENV=production npm start
```

手机/模拟器 `SHAFT_EVENTS_BASE_URL` 改 `http://<电脑局域网IP>:8080/`,
Android 模拟器走 `http://10.0.2.2:8080/`。

---

## 12. 实施检查清单

落地时按这个表逐项确认:

- [ ] `ShaftHmac.kt` 抽出公共工具,`EventReporter.hmacSha256Hex` 改用它
- [ ] `ShaftHmacAuthProvider` 实现 `WebSocketAuthProvider`,签 `uid|ts`(无 peer)
- [ ] OkHttp 单独实例:`pingInterval=30s` + `readTimeout=0`
- [ ] `RobustWebSocketClient` 收到 `hello` 才切 Connected(`onOpen` 不算)
- [ ] `IncomingMessage` 解码层覆盖 `hello`/`msg`/`err`/`pong` 四种
- [ ] **`ChatMessageEntity.localKey = clientMsgId ?? "server:$serverId"`,所有写入走 UPSERT**(去重)
- [ ] 发送侧 optimistic 写本地 `state=Sending`,WS 回声 UPSERT 改 `Delivered`
- [ ] 客户端先卡 `text.length ≤ 2048`,**不要**等 server `err` 才知道
- [ ] 客户端生成 UUID v4 / nanoid 作 client_msg_id
- [ ] 收到 `err.rate_limited` 在 UI 上 1s 内 disable 输入框
- [ ] 收到 `err.frame_too_large` / `bad_*`,**优先**用 `err.client_msg_id` 锚定本地行标记 Failed;无 cmid(超大帧 / bad_json / bad_envelope 等帧级错误)才 fallback 到 "最近一条" 启发式
- [ ] 自己发的消息走"回声路径"渲染,**不要**双渲染
- [ ] 上滑加载更多:`before=items[0].id`,空 → 到顶
- [ ] App 进任何聊天页时 `start()`,**退出整个 app 才** `stop()`(否则就收不到新消息提示了)
- [ ] `uid` 为空(未登录 / RefreshTokenManager 没好)时 retry 而不是崩
- [ ] `NetworkMonitor` 切 ONLINE 时 reset 退避立即重连
- [ ] 收到 401 类失败送 `FatalAuth`,UI 提示(`bad_sig` 配置错 / `ts_skew` 时钟错乱)
- [ ] 单 WS / app 整个生命周期,**不要**进 1v1 才建连 / 退出 1v1 才断 —— sub/unsub 模型已被废弃
- [ ] 客户端发送 `to_uid` 时**严格用 `Long`**(JSON 数字)或 `Long.toString()`(canonical decimal),**不要**让序列化器把 boxed Long 序成对象、把字符串带空格,会被 server `bad_to_uid` 拒
- [ ] 收到 `onClose(1008, "replaced")` 时**不要走重连退避表** —— UI 显式提示"账号在其它设备登录",由用户决定是否切换回来
- [ ] 回复:只对 `state=Delivered && clientMsgId != null` 的行提供「回复」;发送帧带 `reply_to:{uid,client_msg_id}`;optimistic 行用本地原消息内容先画引用块,回声到了再被服务端快照覆盖;`reply_target_not_found` 按普通 err 标 Failed

---

## 附:与 events.batch 子系统的协议对照

| 维度 | events.batch | chat ws |
|---|---|---|
| 传输 | HTTP POST batch | WebSocket text frame |
| 鉴权 HMAC 信封 | `HMAC(rawBody)` 走 header | `HMAC("uid\|ts")` 走 query |
| 身份 | `client_id`(64 hex 匿名) | `uid`(pixiv Long) |
| 限频 | 30/min/client + 1000/min/IP | 5/5s/conn + 200/min upgrade/IP |
| 存储 | shaft-events.db(90 天) | shaft-chat.db(30 天) |
| 实时性 | 60s batch flush | 实时广播 + 200ms 批量入库 |
| 失败语义 | 本地存盘重试到容量满 | 重连;丢失中间消息靠 /history 补 |
| 身份关系 | 完全独立两套 | 同一用户两套 id,不 JOIN |
