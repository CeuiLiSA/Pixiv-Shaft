# 广场：关联作品当作帖子 media 展示

2026-09-17。帖子关联了插画 / 漫画时，作品的分页图在列表与详情里按九宫格展示，规则只有一条：

- **作者没传图** → 关联作品的分页（最多 9 页）填进 media 九宫格，引用胶囊照常保留；点分页进作品页。
- **作者传了图** → 只展示上传图，关联作品只剩胶囊。上传图是作者主动选择的展示内容，优先级最高。

## 数据来源：随帖子携带的 object extensions，不回源

发帖时 `PlazaComposeViewModel` 把作品的 Illust JSON 作为 `objectExtensions.illust` 一并提交
（`CreatePost.objectExtensions`），服务端白名单投影后随帖子返回（`PlazaPost.objectExtensions`）。
读者展示时**不再按 `objectId` 拉 `v1/illust/detail`**，也不进 `ObjectPool`。服务端细节见
pixshaft-api 的 `docs/plaza-object-extensions.md`。

- 作品来源：先取 `ObjectPool` 里的完整版（从作品页「分享到广场」进来时零请求），否则一次
  `fetchFullIllustDetail`；拿不到（离线 / 已删 / 无 medium 图址）就只发胶囊，不阻塞发帖。
- 解析结果只在 ViewModel 内存里缓存一份（完整作品 JSON 可能超过 100 KiB，不进 SavedStateHandle）：
  同一引用的重试发同一份；进程被杀后重试会再解析一次，服务端不把扩展算进幂等 hash，所以不会 409。
- 客户端不裁剪：`Illust.plazaExtensions()` 原样带上 app-api 的 Illust（只在没有 medium 图址时不带）。
  白名单投影全在服务端（去掉 caption / 收藏关注态、截到前 9 页与 40 个 tag、只放行 pximg 图址），
  发帖请求体上限 2 MiB。
- 只有 `illust` / `manga` 携带；`novel` / `user` 依旧只有胶囊。

## 图片不落 COS，也不经过 pixshaft

存的只是 JSON。分页图址是 `i.pximg.net` 的 https 地址，每个读者用 `GlideUrlChild` 走自己的
pixiv 图片链路（含 Referer 与图源改写），只有 Glide 本地磁盘缓存。上传图仍走 `PlazaMediaUrl`
的签名 COS 地址，两者在 `PostView.Tile` 里是同一套九宫格、两种取图模型。

## 展示细节（`PostView`）

- 九宫格从「只认 `PlazaImage`」抽象成 `Tile(key, width, height, url, blur, model, open)`：
  上传图 key 含 viewerUid 与 mediaId，关联页 key 是 `work:<id>:<page>`；key / 尺寸 / 遮罩不变时
  复用 ImageView 与在途请求，只有 url 变了且请求已失败才重发（签名轮换那条老逻辑不动）。
- 单页用 `large`（600×1200）按作品宽高比铺满；多页用 `medium` 方格。
- 遮罩沿用作品流的规则（`IllustMuteStore.isMuted` / `IllustNovelFilter.shouldBlurAi`），并额外对
  R-18 作品打码：广场是所有账号共享的公开流，不能靠 pixiv 账号侧的 R-18 开关兜底。打码用与
  `IllustStaggerRenderer` 相同的 `BlurTransformation(25, 3)`，进 Glide cache key。
- 磁盘快照校验（`PlazaRepository.restorePage`）多一条 `objectExtensions.illust.id > 0`。

## 验证

- pixshaft-api：`npm test` 322 项全绿（新增 3 项：投影 / 拒绝用例 / 增量迁移；`media-http`
  的体积断言随发帖路由放宽到 64 KiB 同步更新）。
- app：`testGithubDebugUnitTest --tests 'ceui.pixiv.plaza.*'` 104 项全绿（新增 `PlazaLinkedWorkTest`
  3 项、`PostView` 关联页渲染 1 项、发帖携带 / 重试复用 / 小说不带 / 离线降级 1 项）。
- 未做：真机对拍、androidTest（`compileGithubDebugAndroidTestKotlin` 在改动前就因
  `PlazaReplyComposerTest` / `PlazaFigmaRenderTest` 缺 `dp` 导入而不通过，与本次无关）。
