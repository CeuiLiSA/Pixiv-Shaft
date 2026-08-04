---
name: genupdate
description: Cut a Pixiv-Shaft release — bump version, tag, draft a concise changelog, create GitHub release marked latest, upload the APK with the project's naming convention. Use when the user says /genupdate or asks to "发版 / 出新版本 / cut a release".
---

# /genupdate — Pixiv-Shaft 发版流水线

把「改版本号 → commit/push → tag → CI 自动 build/建 release → 补 changelog」一把梭。每步都要先看上次怎么做的，不要凭记忆推。

约束（项目偏好，别违反）：
- **直接 push 到 `classic`**，不开 PR、不开 feature branch
- **Tag 必须指向版本号 commit**（`chore(release): X.Y.Z`），不能指向更早的功能 commit
- **更新日志要精简**——三段 `新功能 / 重要修复 / 其它`，每条一句话，能合就合
- **APK 命名 `PixShaft_{version}_classic.apk`**——大小写、下划线位置严格照搬历史 release

## 流程

### 0. 起手 sanity

```bash
git status                                          # 必须 clean 或只有 app/build.gradle
git log --oneline -1                                # 确认在 classic 顶部
gh release list --repo CeuiLiSA/Pixiv-Shaft -L 3    # 拿到上一版本号 = $PREV
```

如果 `app/build.gradle` 还没改版本号，问用户要新版本号；如果用户已经手改了，从 diff 里读出来。

### 1. Commit + push 版本号

```bash
git add app/build.gradle
git commit -m "chore(release): X.Y.Z"   # 末尾要带 Co-Authored-By,见全局约定
git push origin classic
```

记下这个 commit 的 sha = `$VERSION_SHA`。

### 2. Tag + push tag

```bash
git tag vX.Y.Z $VERSION_SHA            # lightweight tag,不要 -a
git push origin vX.Y.Z
```

**如果 tag 已经存在但指向错的 commit**（比如用户提前 tag 了某个 feature commit）：

```bash
git tag -d vX.Y.Z
git push origin :refs/tags/vX.Y.Z
git tag vX.Y.Z $VERSION_SHA
git push origin vX.Y.Z
```

这步是 force-tag，移动前主动跟用户确认一句。

### 3. 生成精简更新日志

读 `git log $PREV..HEAD --oneline`，按下面规则归三段：

- **新功能** — `feat(*)` 提交；功能向用户可感知的（picker、新筛选维度、新页面入口、深链等）
- **重要修复** — `fix(*)` 中崩溃 / OOM / 数据错位 / 兼容性失效 / GitHub issue 关联的；多个零碎修复合成一行「多处崩溃和内存问题」
- **其它** — UI 微调、文案优化、长按手势、纯 polish

精简原则（**这条是 skill 的核心，违反会被打回**）：
- 一条 = 一句中文短句，不带 commit hash、不带 issue #、不带文件名
- 同主题多 commit 合并成一条（比如 3 个 bulk-select 修复 → 「批量选择边框圆角对齐」）
- `refactor` / `docs` / `chore` 全部不要
- 不堆 emoji，不堆形容词，不写"全面优化""大幅改进"这种空话
- 长度参考：单段 3-6 条，总长度肉眼一屏读完

示例（v4.7.1 实战）：

```
v4.7.1 更新日志

新功能
- 接入 pixiv 官方通知和公告中心
- V3 搜索筛选器：插画/漫画加「长宽比」「分辨率」，小说加「正文长度」和「阅读预计用时」
- V3 搜索：会员可按男性/女性向人气排序
- 投稿期间 picker 改 7 行扁平布局，对齐官方
- 支持 shaftintent://search 外部深链调起搜索
- 作者有漫画作品时主页插入漫画 tab

重要修复
- 浏览历史导致的 CursorWindow OOM
- 下载管理器并发崩溃
- 幻灯片 Handler 泄漏
- R18 榜单被全局过滤清空
- 安装更新 API 失效，加浏览器兜底
- 多处崩溃和内存问题
- 搜索固定标签不再被历史挤掉
- 详情页相关作品/评论空态、白边、底部安全区
- 批量选择边框圆角对齐
- 反向搜图错误文案优化

其它
- 详情页标题/作者长按复制
- ID 搜索自动剥离非数字字符
- 长按热门标签直接看代表插画
```

**草稿先给用户过一遍**，他改完再上传。

### 4. 等 CI 出 release（v4.8.3 起全自动）

**tag push 就是发版触发器**——`.github/workflows/gradle.yml` 对 `v*.*.*` tag 自动：
build release APK → 命名 `PixShaft_{ver}_classic.apk`（classic 分支）→ 算 SHA-1/SHA-256 →
建 release 并上传 APK。**不要手动 `gh release create`，也不要本机 build / 上传 APK。**

```bash
gh run list --repo CeuiLiSA/Pixiv-Shaft -L 3          # 找到 tag 触发的 run
gh run watch <run_id> --repo CeuiLiSA/Pixiv-Shaft     # 等它跑完（约 10 分钟,建议挂后台）
```

**watch 的退出码不可信，跑完必须 `gh run view <run_id>` 看每步的 ✓/X。**
如果 `Build release apk` 挂在 `lintVitalGithubRelease`：那是 debug 构建不跑的
release-only lint（NewApi fatal 常客），修掉 → commit → 把 tag 挪到新 head
（`git push origin :refs/tags/vX.Y.Z && git tag -d ... && git tag ... HEAD && git push origin vX.Y.Z`）
→ CI 会重跑。release 还没建出来时挪 tag 无损失，不必等用户确认。

### 5. 补 release notes

CI 建的 release notes 是兜底文案「Release vX.Y.Z」+ SHA 校验块（tag 是 lightweight，
没 message 可提取）。用 `gh release edit` 把用户过完的 changelog 写进去，
**末尾保留 CI 算好的 SHA 块**（从旧 body 里抄）：

```bash
gh release view vX.Y.Z --repo CeuiLiSA/Pixiv-Shaft --json body -q .body   # 抄 SHA 块
gh release edit vX.Y.Z --repo CeuiLiSA/Pixiv-Shaft --latest --notes "<changelog + SHA 块>"
```

最后输出三条链接：release page、APK 下载链接、tag 链接。

## 常见 trap

- **lintVital 拦发版**：release 构建才跑 `lintVitalGithubRelease`，NewApi 是 fatal——
  平时 debug 全绿不代表能发版。修复要 pre-26 等价实现，不要塞 baseline
- **versionCode**：近期规律是每版 **+100**（4.8.0→41000、4.8.1→41100、4.8.2→41300、
  4.8.3→41400），不是老公式 `major*10000+minor*100+patch*10`；对不上停下问
- **GitHub release 已存在**：用 `gh release edit` 改 notes，不要 delete-recreate（download_count 会丢）
- **tag 已 push 但指错**：release 已建出来（有下载数）时移动 tag 要先跟用户确认；
  release 还没建出来（CI 失败）时直接挪
