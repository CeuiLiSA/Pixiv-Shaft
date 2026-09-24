# V3 平板界面设计

本目录按 [V3 设计哲学](../v3-design-philosophy.md) 重写，版本日期为 2026-09-09。交付范围包括画廊、发现页、共享样式、交互脚本、状态演示和截图；Android 已接入第一阶段（侧边导航栏、宽窗口页头、自适应列数、去掉双栏空占位），见下文「Android 接手位置与实施顺序」。

## 页面与使用方式

| 页面 | 用途 | 预览 |
| --- | --- | --- |
| [画廊](index.html) | 浏览、筛选、收藏；按需展开详情；全屏查看 | [横屏](gallery.png) · [详情](detail.png) · [深色](dark.png) · [竖屏](portrait.png) · [手机](mobile.png) |
| [发现](magazine.html) | 封面专题、分类浏览与作品集合 | [横屏](magazine.png) · [深色](magazine-dark.png) · [竖屏](magazine-portrait.png) · [手机](magazine-mobile.png) |
| 放大字体 | 200% 文字下检查布局 | [画廊](large-text.png) · [发现](magazine-large-text.png) |

从仓库根目录启动静态服务：

```sh
python3 -m http.server 8766
```

打开 `http://localhost:8766/docs/tablet-design/`。发现页保留 `magazine.html` 文件名以兼容旧链接，现在使用统一 V3 视觉，不再维护独立的纸色与衬线字体主题。

两页的「预览设置」提供浅色/深色、紫色/玫瑰/青绿，以及正常/加载中/加载失败状态。可用 `/` 聚焦搜索，`Esc` 关闭当前浮层，全屏查看时用左右方向键切图。收藏只在当前页面会话中保存；画廊提供模拟下载队列，不请求原图或调用账号接口。刷新后重置示例状态。

## 先确定内容与操作

| 页面 | 业务对象 | 主要操作 | 页面结构 |
| --- | --- | --- | --- |
| 画廊 | 作品列表 | 选择作品并查看详情 | 页面标题 → 排序与分类 → 作品网格 → 按需显示详情 |
| 发现 | 专题与作品分类 | 打开专题或作品 | 页面标题 → 一处专题主视觉 → 分类卡 → 作品网格 |
| 详情 | 当前作品 | 收藏或全屏查看 | 作品预览 → ID 与分类 → 动作 → 原作链接 → 同类作品 |
| 预览设置 | 原型展示状态 | 切换主题或模拟状态 | 简短说明 → 主题 → 强调色 → 内容状态 |

画廊未选择作品时使用完整内容区，不保留 Logo 或空详情占位。发现页的主视觉展示作品本身，避免将推荐计划中的票卡、PRO、奖励或营销文案带入内容页。

## 视觉规则与源码对应

基础颜色、字体、圆角、间距和动效引用同一份设计套件；本目录不复制另一套紫色 HEX 或字体声明。

| 角色 | 本方案 | 来源 |
| --- | --- | --- |
| 页面与表面 | `bg`、`surface`、`surface-2`；图片周围保持中性 | [tokens.css](../../mockup/v3-design-system/tokens.css) |
| 操作与选中 | 主按钮 `primary / primary-ink`；次按钮和选择态 `tint / on-tint` | 同上 |
| 文字 | 标题 `ink`；辅助信息 `muted`；主视觉说明 `on-hero` | 同上 |
| 英文与数字 | Montserrat 400 / 500 / 600 / 700 / 800；中文使用系统回退 | [fonts.css](../../mockup/v3-design-system/fonts.css) |
| 页面标题 | 32px / 700；窄屏 28px | [common.css](common.css)、页面样式 |
| 主视觉标题 | 28–40px / 800；正文 14px / 400 | [magazine.css](magazine.css) |
| 分区与卡片标题 | 20px / 700；15–16px / 600 | 同上与 [style.css](style.css) |
| 控件与辅助文字 | 14px / 500–600；12px / 400–500；英文分类标记 11px | 同上 |
| 形状 | 主视觉 30/26；面板 24；作品/内容卡 22；局部图标 17；按钮胶囊 | 套件变量 |
| 留白 | 页面 32，手机 20，极窄画廊 16；卡片间距 16–24 | 本目录的布局规则 |
| 操作热区 | 原型至少 48 CSS px；Android 对应至少 48dp | 共享控件样式 |
| 动效 | 160–200ms 状态反馈、按下 0.96；减少动态效果时关闭 | 共享组件与媒体查询 |

作品图片不旋转，图片尺寸由内容决定。选中态只加主题细描边与浅色底；普通作品卡不同时使用浓阴影、彩底和粗描边。发现页的专题卡采用轻描边与 22px 圆角，排布不再以错位、衬线字和杂志刊头制造另一套风格。

## 文案与示例资料

标题用于定位功能：「推荐作品」「分类浏览」「关注动态」「我的收藏」。动作写「查看风景作品」「收藏作品」「模拟下载」；反馈写「已收藏（演示）」「已取消收藏」；空态区分「暂无收藏」与「暂无匹配作品」。

不替用户表达喜欢，不使用「留一页给下一份喜欢」「遇见故事里的你」「灵感」「浪漫」等情绪引导，也不加入英文口号。英文只作结构标签，例如 `ARTWORK FEED`、`CATEGORIES`、`ARTWORK DETAILS`。

本地缩略图没有完整的作者和原始标题信息，因此统一显示「作品 + ID」，不编造画作标题、画师姓名、头像或关注关系。分类仅为布局示例；原作资料通过 Pixiv 链接查看。分享、关注与评论的真实流程仍由现有业务负责，不用占位数据模拟成功。

图片来源：画廊与分类卡复用 `app/src/main/assets/prime_square`；发现页封面沿用 [作品 13534647](https://www.pixiv.net/artworks/13534647) 的已存在本地预览 `magazine-assets/dusk.jpg`。图片归原作者所有，本轮未新增外部图片下载。

## 窗口与分栏

根据实际可用窗口和内容容器计算布局。物理分辨率、设备名称、横屏方向都不能单独决定分栏。Android 官方窗口宽度档位为 `<600`、`600–839`、`840–1199`、`1200–1599`、`≥1600dp`；高度低于 480dp 也需要单独考虑布局。这些是平台档位，本方案的内容最小宽度是产品选择。[Android 窗口尺寸指南](https://developer.android.com/develop/ui/views/layout/use-window-size-classes)

| 浏览器可用窗口 | 画廊导航 | 未选中作品 | 选中作品 |
| --- | --- | --- | --- |
| <600 CSS px | 底栏 | 默认两列，大图模式单列 | 详情占内容区；关闭恢复列表 |
| 600–1039 CSS px | 88px 侧栏 | 根据实际列表宽度自动计算列数 | 单页详情，保留当前选择 |
| ≥1040 CSS px 且高度 ≥480 CSS px | 88px 侧栏 | 全宽作品流 | 列表 + 400–480px 详情 |
| 高度 <480 CSS px | 按宽度选择导航 | 列表可滚动 | 单页详情，避免低矮双栏 |

1040px 是本原型的保守断点，不是 Android WindowSizeClass。按侧栏 88、列表有效宽度至少 448、列表左右留白 64、详情 400，加上边线与滚动条余量估算，需要约 1000px 以上。旧方案直接在 960 处分栏会压缩有效列表宽度，本轮改为 1040，并在 1039/1040 两侧检查布局。

Android 实现时重新计算 `rail + feed padding + feed minimum + detail minimum + dividers + insets`。系统 inset、铰链、窗口装饰及字体缩放变化后，任一 pane 无法满足内容要求就回退单页；不能把 1040px 原样写成全局 dp 判断。

列表列数按 **当前 pane 的有效宽度** 计算：

```text
columns = max(1, floor((contentWidth + gap) / (minCardWidth + gap)))
cardWidth = (contentWidth - (columns - 1) * gap) / columns
```

`contentWidth` 已扣除内边距。原型常规/紧凑/大图最小卡宽为 198/152/260px，间距 16px；手机默认两列，大图单列。正式 App 继续使用真实图片比例与现有长图限制；原型素材为方形缩略图，不能把当前网格误当作原生瀑布流实现。

发现页按宽度将封面、分类目录收成单列；分类卡从三列变为横向内容卡，再收成手机单列。作品网格从四列降为三列、两列。正文或动作较长时换行，不以缩小字号维持列数。

## 状态与返回契约

- 未选择作品时只有列表；选择后展开详情；连续选图替换当前选择。
- 全屏 → 当前详情 → 列表，`Esc` 每次只关闭一层。关闭后将键盘焦点交回有效的触发元素；原卡片因过滤消失时使用可见入口兜底。
- 宽窄切换保留当前详情；恢复宽屏后列表重新出现。原型按作品 ID 与顶部偏移恢复打开/关闭详情和调整密度时的列表锚点。
- 卡片与详情的收藏状态同步；在收藏页取消最后一项后显示空态。原型收藏仅为会话数据，未实现账号持久化。
- 模拟下载以作品 ID 去重，重复点击不追加相同条目。队列明确标注演示，不冒充已下载文件。
- 加载与失败在预览设置中手动触发，保留筛选条件；重试恢复原列表。无真实请求时不伪造百分比或自动成功倒计时。
- 在输入框内不拦截普通键盘输入；全屏查看的左右键只作用于当前浏览序列。浮层支持 Escape 与焦点恢复。

## Android 接手位置与实施顺序

### 第一阶段：已实施（2026-09-24，issue #1087）

形态只看**当前窗口**的可用宽度（`Configuration.screenWidthDp`），不看设备型号或横竖屏。`MainActivity` 声明了 screenSize 等 configChanges、不重建，旋转、分屏和双栏展开后在 `onConfigurationChanged` 重新判断。

| 位置 | 行为 |
| --- | --- |
| [HomeShellHost.kt](../../app/src/main/java/ceui/pixiv/ui/navigation/HomeShellHost.kt) | 可用宽度 ≥600dp（Android medium 档下限）时侧栏代替底栏；tab 页通过 `HomeShellHost.observe` 订阅形态 |
| [HomeNavigationRail.kt](../../app/src/main/java/ceui/pixiv/ui/navigation/HomeNavigationRail.kt) | 88dp 侧栏：顶部菜单按钮 → 与底栏同序的 tab（含 R18 / 我的开关）→ 分割线 → 「收藏 / 下载」快捷入口。选中项 `V3Palette.alpha15` 底 + `textAccent` 字，22dp 圆角，热区 ≥64dp；条目多于窗口高度时整列滚动；替整页吃掉起始侧与上下系统 inset。AppTheme 是 AppCompat，不使用 Material `NavigationRailView` |
| [FragmentLeft.java](../../app/src/main/java/ceui/lisa/fragments/FragmentLeft.java) | 宽窗口隐藏紫色 Toolbar 与 TabLayout，换成「推荐」标题行 + 「推荐作品 / 热门标签」分段切换；手机排版不变 |
| FragmentCenter / FragmentRight | 宽窗口隐藏标题行里的抽屉按钮（菜单入口在侧栏），标题对齐 24dp 页边距 |
| [StaggeredManager.java](../../app/src/main/java/ceui/lisa/helper/StaggeredManager.java) + [AdaptiveStaggerColumns.kt](../../app/src/main/java/ceui/pixiv/ui/common/AdaptiveStaggerColumns.kt) | 瀑布流列数按列表自身宽度计算：「每行几列」2/3/4 视为 360dp 上的列数（最小卡宽 180/120/90dp），更宽时保持卡片物理尺寸多排几列，窄时不少于设置值。在 `LayoutManager.onMeasure` 改列数，同一帧生效。覆盖推荐流、通用插画流、浏览历史、详情页相关作品、广场 |
| [SpacesItemDecoration.java](../../app/src/main/java/ceui/lisa/view/SpacesItemDecoration.java) | 按 LayoutManager 实际列数给间距（边缘 8dp、中缝 8dp），不再只认 2/3/4 列 |
| [TabletActivityEmbedding.kt](../../app/src/main/java/ceui/pixiv/ui/embedding/TabletActivityEmbedding.kt) | 删除 `SplitPlaceholderRule` 与占位 Activity：没打开详情时首页独占整窗（显示侧栏），打开详情后首页落在 3/7 窄栏、自动回到底栏排版 |

### 第二阶段：待实施

以 Views / Fragment 在首页内增加「列表 + 400–480dp 详情」宿主，继承主入口排序、R18/“我的”开关与渠道能力门控。新宿主与旧 Activity Embedding 规则必须互斥，避免重复分栏。时间线模式（单列大卡）在宽窗口下需限宽，目前仍铺满。

| 位置 | 当前行为 | 后续实施 |
| --- | --- | --- |
| [V3Palette.kt](../../witstudio/src/main/java/ceui/pixiv/witstudio/theme/V3Palette.kt) | 宿主主题派生色和对比度处理 | 复用真实 App 主题；不以 Web HEX 替换全局资源 |
| [WitRowStyle.kt](../../witstudio/src/main/java/ceui/pixiv/witstudio/theme/WitRowStyle.kt) | 设置分段行 | 设置分类采用 20/5 外内角与 2dp 行隙 |

详情嵌入前检查 `activity.finish()`、`TemplateActivity` 跳转、返回广播、Insets 和工具栏所有权。不要把 `(MainActivity, *)` 放宽为 `(*, *)`；现有注释已记录链式跳转与 finish 连带关闭问题。多 Activity 向统一宿主的迁移须明确 pane 内替换、pane 内推进和全屏路由。

其余页面沿用 V3 页面配方：下载使用列表/详情与条件出现的批量操作；设置使用分类区 + 限宽分段行；作者页突出作品；小说正文限宽；漫画双页遵循实际页序与 RTL。正式实施仍需检查登录态、屏蔽、私密收藏、账号切换及 Lite 渠道，不由设计原型改变这些规则。

## 验证与交接

[verify.cjs](verify.cjs) 使用 Playwright + 本机 Chrome，服务器根目录须为仓库。环境已安装 Playwright 时运行：

```sh
node docs/tablet-design/verify.cjs
```

如果 Playwright 不在 Node 默认搜索路径，可用 `PLAYWRIGHT_MODULE` 指定已安装模块路径。可用 `TABLET_PREVIEW_URL` 改变页面地址。脚本会更新本目录截图，断言失败时非零退出。

2026-09-09 本轮执行通过：两页的 320/390/600/768/960/1039/1040/1280/1440/1920 宽度、深浅 × 三配色、四种窗口下的 200% 实际文字、长标题、420px 低高度窗口、图片加载、收藏一致性、搜索空态、详情跨断点保留、全屏键盘操作、关闭焦点、模拟下载去重和加载/失败/重试。

共享组件图鉴的检查同时通过：Montserrat 五档字重加载成功，60 组主题文字/背景配对的对比度均不低于 4.5:1。截图已按当前源码重新生成。失败状态见 [画廊](error.png) 与 [发现页](magazine-error.png)。

浏览器结果不能替代原生验收。Android 后续须覆盖 TalkBack、系统字体缩放与 Insets、旋转/分屏/铰链、进程恢复、图片真实尺寸、失败回滚、RecyclerView 性能及账号切换。本原型不宣称已完成这些验证。

## 文件职责

- `common.css`：引用 V3 套件，提供两页共享的导航、搜索、按钮、浮层及状态布局。
- `style.css` / `magazine.css`：只定义画廊和发现页各自的布局。
- `preview.js`：统一日夜、强调色和内容状态演示。
- `app.js` / `magazine.js`：各自的浏览、过滤、收藏与查看器交互。
- `data.js`：共用本地图片索引，不写虚构原作资料。
- HTML 与截图：可交互页面及其实际渲染记录。

后续调整通用角色应改设计套件与 V3 规范；只影响平板分栏的参数留在本目录。不要重新维护一套平板专用品牌色、字体或口号。
