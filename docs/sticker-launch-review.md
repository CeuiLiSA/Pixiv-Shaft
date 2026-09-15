# Sticker 上线审查（2026-09-15）

## 标的

按 launch-review 的 local 模式审查本次 sticker 改动：Android 共用下载/本地加载组件、聊天消息、广场回应；pixshaft-api 的目录及回应接口；shaft-api-v2 的聊天协议及数据库迁移。排除其他会话的后台、推荐、备份等改动；Pixiv 作品评论不接入。

## 已修

1. `app/src/main/java/ceui/pixiv/sticker/StickerRepository.kt:150`：旧 Glide 请求在新资源已就绪后失败，会覆盖新 Ready 状态。提前拒绝旧 generation，并用 compareAndSet 只修改该请求读取的状态。
2. `app/src/main/java/ceui/pixiv/sticker/StickerRepository.kt:55`、`StickerStore.kt:161`：反复打开面板会重复读取全部 ZIP 和解压文件做 SHA/CRC，造成无谓磁盘读取。首次完整校验后记录文件清单，后续在 IO 线程验证文件及标记的存在、长度、修改时间，变化时完整修复。
3. `app/src/main/java/ceui/pixiv/sticker/StickerImageView.kt:37`：下载进度刷新会重复清理每张已挂载图片；禁用内存缓存又使列表回滚重复解码。只观察 Ready 变化，并使用包含版本和资源尺寸的内存缓存键。
4. `app/src/main/java/ceui/pixiv/sticker/StickerPicker.kt:87`：WitDialogBuilder 重设内容 LayoutParams，使网格高度不能依赖原始 LayoutParams。内容容器在测量阶段限定高度；验证窄屏、日夜主题与两倍字号的 Ready/Failed 切换。
5. `app/src/main/java/ceui/pixiv/sticker/StickerModelLoader.kt:17`：按 View 物理像素挑选 ZIP 资源会使高密度手机的选择器使用大资源。改为显式 resourceSize：选择器/广场小图使用 64 ZIP，聊天消息使用 128 ZIP；与 View 大小无关。

## 确认的关键链路

- Tokyo 只返回 JSON；公开目录 URL 指向 COS public/stickers，Tokyo ZIP 路径返回 404。
- 四份 ZIP 已匿名完整下载验证长度及 SHA-256，包含 animation/pkg-128.zip。
- 所有 ZIP 保留，分别写 downloaded.json 和 extracted.json，最后写 ready.json。中断、坏校验和、ZIP 路径越界、解压文件缺失均不能进入 Ready；重试复用已验证 ZIP。
- Glide 只接收 LocalSticker，专用 Fetcher 返回本地文件流和 DataSource.LOCAL，没有远程回退；resourceList.url 仅用于匹配包内路径。
- 64 位 sticker ID 在 JS、WebSocket 和 SQLite 中保持十进制字符串；目录响应保留原始 JSON，Android 使用 Long。聊天 Room 3→4 只加列。
- 弹窗关闭取消 UI 观察，下载继续；聊天回调检查 Fragment view 生命周期。
- 关键状态日志使用 Sticker-System，正式版也保留；首次读取各档资源记录 source=LOCAL、resource_size 和文件路径。

## 部署

- Tokyo：/home/ubuntu/pixshaft-api，已更新并重启 pixshaft-api、重载 Caddy；10 个目标文件 SHA-256 与本地一致。
- Tokyo 备份：/home/ubuntu/stickers-deploy.oNU09k/before。
- 聊天按既有拓扑部署 shaft-v2 的 /root/shaft-api-v2，5 个文件 SHA-256 与本地一致；备份 /root/shaft-stickers.SvZCCr/before。此服务不承担 ZIP 下载。
- PM2 两个服务在线；目录公网请求 200，未知类型 {}，ZIP 路径 404。

## 校验记录

- pixshaft-api 相关测试 10 项通过；Tokyo 暂存目录运行 3 项通过，Caddy 配置校验通过。
- shaft-api-v2 本地及远端各 2 项测试通过，包含临时 SQLite 重开后历史读取。
- 最终资源尺寸版本：Android 编译、APK 构建及 61 项相关单元测试通过；已安装并启动到 Pixel 8。
- 最终版本全量 lint 已运行：189 项既有 MissingTranslation，sticker 文件没有 lint 错误。未扩展修改范围外翻译。
- Pixel 8（37261FDJH004FJ）首次完整下载 134,101,508 字节约 19 秒，随后 gate_ready（4 包、2,159 个贴纸）及 panel_open。
- 真机磁盘确认：4 ZIP、4 下载标记、4 解压标记、1 总就绪标记，无 .part/.tmp 残留。
- 按用户后续要求分批提交并推送；部署保持与提交内容一致。

- 最新版真机广场入口及动画资源显示正常；首次 64 资源解析日志指向 files/emoji/64/customized/drool.png，source=LOCAL。
- 同进程重复打开：15,926 个文件及标记校验约 202 ms，gate_reused 后 panel_open，没有重复下载。
- 最新版真机聊天历史贴纸解析日志指向 files/emoji/128/flags/1f1eb-1f1f0.png，source=LOCAL resource_size=128；本次运行未发现 FATAL EXCEPTION。
