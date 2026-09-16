# 广场：Figma 第二轮对照

设计来源：[Five Degrees / Post](https://www.figma.com/design/XIelCCTiUHLjsJ9BbLmFy3/Five-Degrees?node-id=10025-6103)。通过 Figma Desktop MCP 的 `get_design_context` 读取组件结构、尺寸、字体与原始图标；颜色使用项目 `V3Palette` 和 `v3_text_*` 日夜资源。

## 对照节点

| 页面 | 节点 | 实现中的基准 |
| --- | --- | --- |
| 列表 | `10063:6767`，九宫格 `10067:7658` | 左右 16dp；头像 50dp；组间 12dp；标题 17sp；正文 15sp/1.35；九宫格间距 2dp、格子宽高比 118:110；表情 28dp、间距 4dp |
| 详情 | `10025:7163` | 标题 24sp/1.2；主要内容间距 16dp；评论头像 36dp、头像与正文间距 8dp；子回复缩进 44dp；底部回复共用聊天室输入栏（2026-09-16 更新） |
| 创建 | `13448:9475` | 导航栏 64dp；关闭圆形 40dp；发布按钮 33dp；标题 24sp；正文 Inter Medium 15sp/1.5；照片 80dp、间距 5dp；引用区字号 13/14sp |

上表为默认字号下的 dp/sp 布局。系统状态栏、导航栏和键盘使用实际设备 Insets；字体放大时允许控件增高和表情换行，不固定截图总高度。

## 第二轮修正

- 使用指定行高，避免 Android 字体 metrics 再乘倍数造成正文和标题额外增高。
- 九宫格恢复设计宽高比；表情胶囊宽度、emoji 字号与换行间距对齐设计。
- 缩小评论预览的额外留白，区分作者与内容的文字角色。
- 详情底部补充真实回应数、评论数；评论按钮定位评论或进入回复。
- 评论正文与头像并排，子回复缩进；列表复用时还原正文左边距。
- 创建页设置 inputType 后重新应用 Inter，避免系统重置字体；恢复小尺寸引用入口，隐藏空错误占位。
- 移除可见范围 UI 及其专用图标。所有帖子公开，最多 9 张图，引用为 Pixiv objectId/objectType。

## 验证

- `:app:assembleGithubDebug`、`:app:assembleGithubDebugAndroidTest`。
- `:app:testGithubDebugUnitTest --tests 'ceui.pixiv.plaza.*'`：13 项通过，包含 API 28/35、320/720dp、日夜、两倍字号、评论复用、底部布局，以及草稿/请求状态测试。
- `:safe:testDebugUnitTest`：包含广场 Bearer 白名单与 401 刷新重试。
- Pixel 8：`PlazaFigmaRenderTest` 使用生产 PostView/Header/ReplyBar 和 Figma 原图生成日夜列表、详情截图；`PlazaComposeRenderTest` 打开真实创建页、选择本地测试图片、重建 Activity 并确认草稿和图片恢复。测试不提交帖子。
- 截图输出于应用 external-files 的 `plaza-figma-*`，用于逐次视觉检查；测试图片仅在 androidTest 中打包。

## 数据与设计边界

动态内容来自真实帖子，不硬编码设计中的姓名、时间、计数或照片。Figma 示例的 Quest 映射为 Pixiv 作品/用户引用；视频、可见范围和示例置顶标签不属于本次帖子需求。详情底部回复已改为页内输入与发送，使用聊天室共享布局、引用条和键盘/贴纸面板切换，不再打开独立创建回复页。文字发送失败保留草稿，成功清空并刷新评论；贴纸仍作为帖子回应。创建帖子继续使用原有图片上传与草稿流程。

## 帖子大图（2026-09-15）

帖子图片复用插画二级详情的 `ImageDetailActivity`、`FragmentImageDetail` 和 `SketchZoomImageView`。点击时传入真实缩略图的屏幕矩形，复用展开、竖向拖拽、回弹、缩回和预测式返回动画；不另建 Dialog 或手写缩放控件。翻到其他图片后退出，沿用现有查看器的淡出行为。

广场只提供图片数据源：保留媒体 ID 缓存，签名有效时不请求帖子接口，过期时合并刷新；最多九张图片保持原始顺序，重建后保留当前页。页码使用国际化资源，插画专属的收藏和 AI 操作不用于帖子媒体。

## 详情页内回复（2026-09-16）

按用户确认，底部改为复用聊天室的 `chat_view_composer.xml` 与主题样式。键盘、贴纸面板和导航栏安全区交给 `BottomPanelCoordinator`，详情 Header 不再重复叠加底部 Insets。评论回复在底部显示引用条；发送失败保留草稿和请求 ID，成功清空并刷新，下一次回复使用新的请求 ID。

验证：GitHub Debug 编译、15 项状态/布局单测通过；Pixel 8 的日夜渲染、刷新与退出、键盘和贴纸切换、返回收起、Activity 重建草稿恢复通过。测试未发布评论或回应。完整 lint 因现有 189 项 `MissingTranslation` 报错未通过，本次改动无新增 lint 错误。

## 回应尺寸（2026-09-16）

重新通过 Figma Desktop `get_design_context` 对照 `10131:8487` 和列表 `10063:6767` 的 `10063:6901` Reaction Stack：高 28px，表情 18px、计数 Inter Regular 15px，水平内边距 8px，表情与计数及胶囊间距均为 4px。列表完整节点读取超时后，读取其结构并缩小到 Reaction Stack 获取代码与截图。

原贴纸分支单独设置 48dp 最小高度、32dp 图片和带描边底板，导致列表、帖子和评论中的贴纸回应明显偏大。现改为复用已有回应胶囊，仅将其中的表情替换为真实贴纸资源；64px 解码档位不变，显示尺寸随 18sp 表情缩放，计数和选中态统一。

此次验证：GitHub Debug 编译、8 项现有布局测试通过；Pixel 8 实际帖子与评论的回应已与相邻操作同高，现有日夜渲染及输入交互检查通过。未提交任何评论或回应。
