---
name: genupdate
description: Pixiv-Shaft 发版：确认版本、整理精简中文更新日志、提交到 classic、打标签并跟进 GitHub Actions 自动发布 APK。用户调用 $genupdate、/genupdate，或要求为 Pixiv-Shaft 发版、发布新版本时使用。单纯改版本号、commit/push、安装真机或编辑本 skill，不等于要求发布 release。
---

# genupdate — Pixiv-Shaft 发版

沿用项目 Claude skill 的发布约定，使用 Codex 的终端工具与 `git`、`gh` 完成工作。执行前读取当前 `.github/workflows/gradle.yml`，以实际 CI 为准；不依赖 Claude 专属工具或配置。

## 发布约定

- 仓库为 `CeuiLiSA/Pixiv-Shaft`，直接推送到 `classic`，不开 PR 或 feature branch。
- 版本定义在 `app/build.gradle`。近期 `versionCode` 每版递增 **100**，先核对历史，不使用旧的语义版本计算公式。
- 使用轻量标签 `vX.Y.Z`，指向包含全部待发布改动的最终版本提交，提交信息通常为 `chore(release): X.Y.Z`。
- APK 命名为 `PixShaft_X.Y.Z_classic.apk`。
- 推送标签会触发 CI 编译、签名、创建公开 release 并上传 APK。正常流程不手动创建 release，不上传旧的本地 APK。
- 更新日志精简，分为「新功能 / 重要修复 / 其它」，每条一句中文短句。

## 1. 确认仓库、版本和发布范围

从当前目录、相邻的 `Pixiv-Shaft` 目录，或本 skill 解析符号链接后的实际路径定位仓库。确认 remote 对应上述仓库后，在仓库根目录执行命令，不在其他项目发版。

```bash
git status --short --branch
git diff --stat
git diff --cached --stat
git ls-files --others --exclude-standard
git log --oneline -5
git remote -v
gh auth status
gh release list --repo CeuiLiSA/Pixiv-Shaft --limit 10 --json tagName,isDraft,isPrerelease,isLatest,publishedAt
```

读取版本定义、近期版本提交、当前 workflow，以及上一正式 classic release 的正文和附件。排除草稿、预发布和其他分支的 release；不要直接把列表第一项当作上一版。用上一正式版本标签到本次候选提交的范围生成日志。

版本选择优先级：用户明确指定的版本、当前已改好且尚未发布的版本、在已发布版本上递增 patch。需要新版本时按近期约定同步增加 `versionCode`。沿用已完成的版本升级，不重复加号；用户意图或版本历史有冲突时才询问。

默认仅提交这次发布范围内的改动。用户明确要求全部 local changes 时，检查并纳入相关的已跟踪和未跟踪文件；不提交凭据、临时文件或构建产物。不覆盖其他会话的修改或暂存内容。先完成不依赖答复的检查和准备，再询问无法从上下文确定的范围。

获取最新远端状态并确认 `classic` 与远端关系；不强推分支、不丢弃未知改动。通过 `git ls-remote --tags origin` 核对标签，不能只看本地标签。若只是要求写日志、升级版本或改造本 skill，做到该范围即可，不触发发布。

## 2. 准备更新日志和发布检查

锁定本次候选提交，结合提交历史与实际 diff 检查待发布内容；包括用户授权纳入但尚未提交的改动。

- **新功能**：用户能感知的新能力和入口。
- **重要修复**：崩溃、内存、数据、兼容性等影响使用的问题。
- **其它**：UI、文案、交互的轻量调整。
- 同主题的多次提交合并为一条；没有条目的分类省略，不凑数量。
- 不带 commit hash、issue 编号或文件名，不堆 emoji、形容词或空话。
- 纯 `refactor`、`docs`、`chore` 不写入日志；按实际影响判断，不能只看提交前缀。
- 总长度尽量一屏可读，不把 git log 原样贴进 release。

将完整 Markdown 写入临时 notes 文件，保留真实换行。展示确切版本、发布范围和日志草稿。

**默认先让用户审阅日志，再推送发布标签。** 当前 CI 在标签推送后就会创建公开 release，不能把审阅放在标签之后。用户已批准当前草稿，或明确要求直接发布、不再确认时，沿用授权，不重复询问。若确实需要等待审阅，先完成已授权的检查和修复，并说明这是本 skill 的默认要求，链接到本 `SKILL.md`。

根据实际改动执行必要检查，复用仍然有效的检查结果。当前 CI 包含单元测试、Room schema 检查、全模块 lint、release 构建和后续 instrumentation 测试；以当前 workflow 为准。遇到 release 专属 lint 或编译问题要修复根因，不靠修改 baseline 隐藏错误。

## 3. 提交版本并推送标签

先提交授权的功能改动，再完成最终版本提交：

- 版本升级尚未提交：提交版本文件，使用 `chore(release): X.Y.Z`。
- 当前顶部已经是完整的版本提交：直接复用。
- 版本早已升级，但之后又有本次要发布的功能提交：在这些改动之后补最终 release 提交；若版本文件无需再改，可使用 `--allow-empty` 标记发布点。不能把标签打回旧版本提交而漏掉后续功能。

遵循当前仓库的提交约定，不从 Claude 配置照搬作者或署名。记录最终提交为 `VERSION_SHA`，推送 `classic`，并核对远端分支 SHA。

当前 workflow 会在标签运行中再次 checkout `origin/classic` 或 `origin/master`，因此标签 SHA 本身不足以证明 APK 来自该提交。读取 workflow 确认该行为是否仍存在；若存在，推标签前确认目标分支仍等于 `VERSION_SHA`，发布过程中不再推入无关提交，随后从 CI 日志核验实际 checkout 的 SHA。

确认标签尚不存在后：

```bash
git tag "$TAG" "$VERSION_SHA"
git push origin "refs/tags/$TAG"
```

其中 `TAG` 为 `vX.Y.Z`。如果相同标签已经指向本次提交，继续核对已有 CI 和 release，避免重复创建。标签有冲突时先检查远端标签、已有 release（包括草稿）和运行状态，按下方失败恢复规则处理。

## 4. 跟进对应的 CI 运行

```bash
gh run list --repo CeuiLiSA/Pixiv-Shaft --workflow gradle.yml --event push --commit "$VERSION_SHA" --limit 10 --json databaseId,headSha,headBranch,event,status,conclusion,url
gh run view "$RUN_ID" --repo CeuiLiSA/Pixiv-Shaft --json status,conclusion,headSha,jobs,url
```

按标签、提交 SHA 和事件匹配本次运行，不把同一 SHA 的分支构建或其他最新运行当成标签构建。刚推标签暂时查不到运行时，短暂等待后重查。长时间等待使用后台进程或不超过 60 秒的分段查询，并向用户报告有意义的进展。

明确检查所有必要 job 和运行的最终 `conclusion`，不能只看命令退出码或 release 是否存在。当前 instrumentation job 在上传 release 之后运行，附件出现不代表全部检查通过。同时核验 CI 实际构建的提交；若与候选 SHA 不一致，不能宣称本次候选版本发布验证成功。

### 失败恢复

- runner、网络等临时失败，可有限重跑同一候选版本的失败任务。
- 确认是代码、测试或 lint 问题，先修复并完成相关验证，再提交。
- 仅当该标签没有任何 release（含草稿）或已发布附件、旧运行已经结束或取消，且修复重发属于已有授权时，才可纠正失败标签。先记录远端旧 SHA，使用带明确预期 SHA 的 lease 更新，避免覆盖并发变化。不要循环移动标签掩盖持续失败。
- 已创建 release 或附件时，不擅自移动标签、删除重建 release 或替换 APK。先准备具体修复和重新发布方案；已有明确授权则沿用，否则说明实际影响后请求必要确认。

## 5. 写入日志并验证发布结果

读取 CI 创建的 release：

```bash
gh release view "$TAG" --repo CeuiLiSA/Pixiv-Shaft --json url,body,assets,isDraft,isPrerelease,tagName
```

把已确认的更新日志与 **本次 CI 生成的 SHA1 / SHA256 校验区块** 合并写入 `NOTES_FILE`。完整保留校验内容，不用其他版本或旧本地 APK 的校验值替代。使用文件传递多行正文：

```bash
gh release edit "$TAG" --repo CeuiLiSA/Pixiv-Shaft --latest --notes-file "$NOTES_FILE"
```

最后确认：远端标签指向最终发布提交；CI 实际构建 SHA 与其一致；全部必要检查成功；版本正确；release 为正式公开版本且标记 latest；存在名称正确的 APK、下载链接及校验值。CI 未创建附件时诊断对应步骤，不通过手动创建空 release 掩盖失败。

最终给出 release 页面、APK 下载、tag 三个链接，并如实说明仍存在的 CI 问题或未提交改动。只有完成上述验证才能报告发布成功。
