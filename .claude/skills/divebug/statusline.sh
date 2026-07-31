#!/usr/bin/env bash
# Claude Code 状态栏脚本 —— 给 /divebug 用（项目级，随仓库共享）。
#
# 把当前 session 最近一次 /divebug 钉在底栏，两行：
#   1. 绿色的一句中文 bug 摘要（我正在修什么）
#   2. issue 链接 —— 支持 OSC 8 的终端（iTerm2 / Kitty / WezTerm）里可点，
#      其它终端（如 macOS Terminal）退化成纯文本 URL。
# 两行在整个 run 期间都留在眼前。
#
# 由项目级 .claude/settings.json 的 statusLine 挂上：Claude Code 从 stdin 传入
# 一个 JSON（含 transcript_path / cwd），本脚本从里面取值。
#
# issue 号只从 transcript 里 /divebug 的 command-args 取（不是全文 grep），
# 所以 SKILL.md 里的示例链接不会漏进来；对同一个 session 再 /divebug 另一个
# issue，这一行会自动切到最新那个。
# command-args 既支持完整 URL，也支持裸 issue 号（`/divebug 953`）—— 裸号时
# owner/repo 从 git remote 本地解析，不发网络请求。
#
# 中文摘要读自 per-issue sidecar /tmp/divebug-summary-<N>.txt，由 skill 在
# Phase 1 写入（一行）。文件驱动而非 grep transcript，所以 SKILL.md 的指令正文
# 永远不可能泄漏到状态栏。sidecar 不存在时退化为只显示链接那一行。
#
# 依赖 jq。jq 缺失 / 读不到 transcript / 本 session 没有 /divebug 时静默退出
# （空状态栏）。
command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
tp=$(printf '%s' "$input" | jq -r '.transcript_path // empty' 2>/dev/null)
cwd=$(printf '%s' "$input" | jq -r '.cwd // .workspace.current_dir // empty' 2>/dev/null)
[ -n "$tp" ] && [ -f "$tp" ] || exit 0

# 最近一次 /divebug 的参数原文。
args=$(grep -E 'command-name>[^<]*divebug' "$tp" 2>/dev/null \
  | tail -1 \
  | sed -nE 's/.*command-args>[[:space:]]*([^<]*)<.*/\1/p')
[ -n "$args" ] || exit 0

# 参数形态一：完整 issue URL —— repo 和 issue 号都从里面拿。
url=$(printf '%s' "$args" \
  | grep -oE 'https://github\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/issues/[0-9]+' \
  | tail -1)

if [ -n "$url" ]; then
  repo=$(printf '%s' "$url" | sed -nE 's#https://github\.com/([^/]+/[^/]+)/issues/[0-9]+#\1#p')
  issue_n=$(printf '%s' "$url" | grep -oE '[0-9]+$')
else
  # 参数形态二：裸 issue 号（`/divebug 953` 或 `/divebug #953`）。
  # owner/repo 从 origin 的 remote URL 本地解析,不发网络请求。
  issue_n=$(printf '%s' "$args" | grep -oE '(^|[^0-9])#?[0-9]{1,7}([^0-9]|$)' \
    | grep -oE '[0-9]+' | head -1)
  [ -n "$issue_n" ] || exit 0
  [ -n "$cwd" ] || exit 0
  # 两段式：先削掉尾部的 .git / 斜杠,再抽 owner/repo。分两步是因为 BSD sed
  # 不支持非贪婪量词（`+?`）—— 一步写完在 macOS 上会直接报 RE error。
  repo=$(git -C "$cwd" remote get-url origin 2>/dev/null \
    | sed -E 's#\.git$##; s#/$##' \
    | sed -nE 's#^(git@github\.com:|https://github\.com/)([^/]+/[^/]+)$#\2#p')
  [ -n "$repo" ] || exit 0
  url="https://github.com/${repo}/issues/${issue_n}"
fi

summary_file="/tmp/divebug-summary-${issue_n}.txt"

# sidecar 还没写时的兜底：skill 会在 Phase 1 写一句人工摘要，但在那之前（或者
# 用户直接丢了个链接就让干活）这一行会是空的。所以先用 issue TITLE 顶上 ——
# 通过 gh 取一次并缓存进同一个 sidecar 文件（后续每次渲染都读文件,不重复走网
# 络）。title 来自 gh 的结构化 --json,不是 transcript 全文 grep,所以 SKILL.md
# 的示例文案不会混进来。gh 失败（离线/未登录）就不落文件,只显示链接那一行,
# 下一次渲染再自愈。
if [ ! -s "$summary_file" ] && command -v gh >/dev/null 2>&1; then
  title=$(gh issue view "$issue_n" --repo "$repo" --json title --jq '.title' 2>/dev/null)
  if [ -n "$title" ]; then
    # 原子写,并发渲染不会读到写一半的文件。
    tmp="${summary_file}.tmp.$$"
    printf '%s\n' "$title" > "$tmp" 2>/dev/null && mv -f "$tmp" "$summary_file" 2>/dev/null
  fi
fi

# 第一行：一句 bug 摘要,绿色。只取首行,免得多行文件把状态栏撑爆。
if [ -s "$summary_file" ]; then
  summary=$(head -1 "$summary_file" 2>/dev/null)
  # ESC[32m … ESC[0m = 绿色;末尾换行让 URL 独占第二行。
  [ -n "$summary" ] && printf '\033[32m🐛 %s\033[0m\n' "$summary"
fi

# 第二行：可点的 issue 链接。
# OSC 8 超链接: ESC ] 8 ; ; <url> BEL <text> ESC ] 8 ; ; BEL
printf '\033]8;;%s\a🔗 %s\033]8;;\a' "$url" "$url"
