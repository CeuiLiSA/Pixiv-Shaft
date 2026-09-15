# 广场：Figma 第二轮对照

设计来源：[Five Degrees / Post](https://www.figma.com/design/XIelCCTiUHLjsJ9BbLmFy3/Five-Degrees?node-id=10025-6103)。通过 Figma Desktop MCP 的 `get_design_context` 读取组件结构、尺寸、字体与原始图标；颜色使用项目 `V3Palette` 和 `v3_text_*` 日夜资源。

## 对照节点

| 页面 | 节点 | 实现中的基准 |
| --- | --- | --- |
| 列表 | `10063:6767`，九宫格 `10067:7658` | 左右 16dp；头像 50dp；组间 12dp；标题 17sp；正文 15sp/1.35；九宫格间距 2dp、格子宽高比 118:110；表情 28dp、间距 4dp |
| 详情 | `10025:7163` | 标题 24sp/1.2；主要内容间距 16dp；评论头像 36dp、头像与正文间距 8dp；子回复缩进 44dp；底部输入区 40dp、计数间距 12dp |
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

动态内容来自真实帖子，不硬编码设计中的姓名、时间、计数或照片。Figma 示例的 Quest 映射为 Pixiv 作品/用户引用；视频、可见范围和示例置顶标签不属于本次帖子需求。详情底部回复入口继续复用创建页的回复模式，图片回复使用同一套最多 9 张图的上传与草稿流程。
