---
name: divebug
description: "[Pixiv-Shaft] 拿到一个 bug（issue 链接 / issue 号 / 崩溃栈）后的完整闭环：拉全 issue → 定位代码路径 → 钉死 root cause → 最小最佳实践修复 → 编译校验 → 按项目约定 commit/push → 收尾报告（并把一句中文摘要钉到状态栏）。当用户丢来一个 bug 让你「理解这个 bug，找到 root cause，最佳实践修复」时使用。"
---

# /divebug — 一个 bug 的完整下潜

形状：**拉全 → 看懂 → 钉死 → 最小修 → 校验 → 落地 → 汇报**。

每一步都能悄无声息地糊过去，这个 skill 的全部价值就是**拒绝跳步**。

约束（项目偏好，别违反）：
- **直接 push 到 `classic`**，不开 PR、不开 feature branch（和 `/genupdate` 一致）
- commit message 用中文：`fix(scope): 一句话说清改了什么 (#N)`，scope 照抄 git log 里已有的（`image` / `download` / `net` / `novel` / `user` / `chat` / `search` / `lint` / `res` …）
- **改动范围必须等于 bug 范围**，不夹带顺手重构

---

## Phase 1 — 把 issue 拉全

别只信标题，也别只信最后一条评论。

```bash
gh issue view <N> --repo CeuiLiSA/Pixiv-Shaft --json title,body,state,createdAt,labels,comments
```

- **读 body**，不是读标题。复现步骤、期望 vs 实际、截图、崩溃栈全在 body 里。
- **读评论，但要分辨噪音**。路人评论经常在描述*另一个*问题；**原报告人的 body 才是唯一事实源**。
- **截图必须看**：`curl -sL <url> -o /tmp/divebug-<N>-1.png` 然后用 `Read` 打开。视觉证据经常直接推翻文字描述。
- **崩溃栈要看清是哪个变体**：`applicationIdSuffix` 是 `.cshaft` = debug 包，`.pshaft` = release 包；`IS_LITE` 为 true 表示 google(Play) 渠道。
- **记下报告人的 app 版本**。对着一个早就修过的版本报 bug 是常态 —— 先 `git log --oneline <tag>..HEAD -- <相关目录>` 看看是不是已经修了。
- issue 也可能其实是个外部 PR（本仓有活跃外部贡献者）。是 PR 就走 `gh pr view <N>`，先看它的 diff 再决定是补它还是自己重写。

body 稀到没法动手（只有一句「用不了」）→ **停下来问清楚**，不要猜着修。

**把一句中文摘要钉到状态栏。** 读完 issue 后，写一句「症状」（不是 root cause）到 `/tmp/divebug-summary-<N>.txt`，一行、≤ 30 字左右、大白话：

```bash
echo '旧版下载的图片在新版里识别不出已下载状态' > /tmp/divebug-summary-953.txt
```

`statusline.sh` 会把它显示成状态栏第一行（绿色），第二行是可点的 issue 链接 —— 整个 run 里「我在修什么」都摆在眼前。

---

## Phase 2 — 追代码路径

定位到**产生症状的那一行 file:line**。不要从架构推，要从真实代码推。

- grep 截图里的用户可见文案（`画师ID:`、报错文案、日志行），或 body 里提到的 `strings.xml` key / resource id。
- 从「功能入口 → 渲染 → 行为」一路跟到底，别停在入口。
- **⚠️ 本项目有 V2 / V3 两套 UI 并存，先搞清用户在哪一套上。** 这是本仓最高频的误修来源：
  - 老那套在 `ceui/lisa/`（`UActivity`、`FragmentSingleIllust`…），新那套在 `ceui/pixiv/`（`UserActivityV3`、`ArtworkV3Fragment`、`ui/search/v3/`…）
  - 路由开关是 `Shaft.sSettings.isUseArtworkV3`（例：`app/src/main/java/ceui/lisa/activities/UActivity.kt:134` 命中就直接转 `UserActivityV3` 并 `finish()`）
  - 用户截图长什么样、有没有说「新界面/旧界面」，决定你该改哪棵树。**两棵都有同样的 bug 是常态 —— 见 Phase 3。**
- 涉及文案改动时记得 `values-en / -ja / -ko / -ru / -tr / -zh-rTW` 这几套 locale，`scripts/find_missing_used_strings.py` 能帮你查漏。

---

## Phase 3 — 钉死 root cause

**动手之前，先用一句话把 root cause 说出来**：*「`file.kt:LINE` 用 Y 做了 X，但 Y 在 Z 情况下是错的。」*

说不出这句话，就是还没找到，继续 Phase 2。

- **读这几行的 git 历史**：`git log -L /pattern/,+5:<file>` 或 `git log --oneline -- <file>`。上一次「修复」引入本次 bug 是常态；知道它当时在修什么，才不会把*上一个* bug 修回来。
- **找同模式的姐妹文件。** copy-paste 的 UI 代码里跨文件传播是默认情况：
  - V2 有 → 去 V3 找同名对应物（反之亦然）
  - 一个 holder / fragment / adapter 有 → grep 出所有兄弟一起看
  - **一次 commit 修全部实例**。修一半 = 用户第二次来报同一个 bug。
- 看 memory（`MEMORY.md`）里有没有约束这次修法的项目惯例。

---

## Phase 4 — 最小修复

修复的尺寸要等于 bug 的尺寸。

- **不夹带顺手重构。** 一个 commit 只讲一件事。
- **优先删掉坏代码，而不是给它打补丁。** 某个 Span / fallback / 分支就是 bug 源头且没有明确价值 → 删。视觉结果等价 + 代码更少 + 少一个未来的回归点。
- **删代码就顺手删掉随之失效的 import / 变量**，别留半截清理。
- **别为了修一行 bug 新造 feature flag / 抽象层 / helper。**
- **别给「不该发生」的路径加 try-catch 兜底** —— 那是把下一个 bug 藏起来。本仓已经吃过一次「兜底吞异常」的教训（见 `fix(net): review 回补——守卫挂最外层、放行 CancellationException`），要兜就兜在正确的层，且**永远放行 `CancellationException`**。
- 调平台 API 时注意 `minSdk 24 / compileSdk 36`：**lint 的 `NewApi` 是 fatal**，越版本调用在编译期无感、只在老机器上炸成 `NoSuchMethodError`。

---

## Phase 5 — 校验

Agent shell 里 `gradlew` 大概率找不到 Java，先给它一个 JDK：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

按改动性质选（github 渠道 = 用户实际装的那个）：

| 改了什么 | 跑什么 |
|---|---|
| 任何 Kotlin/Java 代码 | `./gradlew :app:compileGithubDebugKotlin` |
| 碰了平台 API / 新 framework 调用 | 追加 `./gradlew :app:lintGithubDebug`（`NewApi` fatal，存量在 `lint-baseline.xml`） |
| 纯逻辑（下载/网络/feed/websocket 等有测试覆盖的） | `./gradlew :app:testGithubDebugUnitTest`，并考虑**补一个复现该 bug 的测试** |
| 纯 UI / 布局 | 编译过 ≠ 长得对 —— **明确告诉用户你没有真机渲染验证过** |

提交前 `git diff` 自己的改动，专门找：误碰的无关文件、忘删的调试 log、漏清的 import。

---

## Phase 6 — 落地

```bash
git status                       # 先看有没有无关的脏文件
git add <file1> <file2>          # 按显式路径 stage,永远不要 git add -A
git commit -m "fix(scope): 中文一句话 (#N)"   # 末尾带 Co-Authored-By,见全局约定
git push origin classic
```

- **`git add -A` 会把用户手上的在做工作静默打包进你的 bugfix commit。** 显式路径，每次。
- **commit subject 里带上 issue 号** `(#N)`，GitHub 才会自动关联。
- **不 amend 已 push 的 commit**。修错了就再推一个。
- **不用 `--no-verify` 绕 hook**，hook 挂了就去修 hook。
- 修完想让用户装包验证 → 让他本机 build，**不要替他跑 `assembleGithubRelease`**（耗时长 + 签名钥匙在他手里，和 `/genupdate` 同一条约定）。

---

## Phase 7 — 收尾报告

一次 run 结束时，给一个一眼能看完的落地小结，顺序固定：

1. **一句话 root cause**（`file:line` + 为什么错）
2. **改了哪些文件**，姐妹文件是一起修了还是确认过不受影响
3. **校验状态**：哪个 gradle 任务绿了；UI 改动要明写「未真机渲染验证」
4. **落地位置**：`已 push → classic @ <短 sha>`；没推就写 `未推送（原因）`，不要留空
5. **最后一行**，单独一行，完整可点的 issue 链接（不要写成裸 `#N`，不可点）：

```
🔗 https://github.com/CeuiLiSA/Pixiv-Shaft/issues/953
```

---

## 反模式（都踩过）

- 「标题说 X 坏了，那我修 X」—— 不读 body 的话，X 可能只是报告人描述的真问题 Y 的下游表现。
- 「最新那条评论说 Z」—— 非报告人的评论基本都是另一件事。以 body 为准。
- 「这 bug 只在一个文件里」—— copy-paste 的 UI 几乎一定有孪生兄弟，**必 grep**。V2/V3 两套树尤其如此。
- 只修了 V3 忘了 V2（或反过来），用户一关 `isUseArtworkV3` 就复现。
- 为了「保留原设计意图」而选了个别扭的修法 —— 有时候原设计本身就是 bug，删掉才是修。
- 把 bugfix 和本地在做的改动一起 commit。`git diff --stat` 先看，只 stage 自己的。
- 加个 catch 把异常吞掉当修复。

---

## 卡住了

两种情况值得**停下来问**，而不是硬猜：

1. **复现不出 body 里描述的路径** —— 问用户走的哪个 UI 入口、V2 还是 V3、哪个渠道/版本号。
2. **找到了坏的那一行，但判断不出正确值该是什么** —— 别推一个猜测。要么问，要么推一个**只加日志的诊断 commit**，让下一份 bug 报告带更多数据回来。
