# 标签流迁移（#1146）

原 `flowlayout-lib` 已删除。复用现有 V3 标签流的 Flexbox 布局引擎和主题表面：

- `WitTagFlowView`：位于 `witstudio`，只认识条目、View、主题、事件和选择。
- `WitTagItem`：稳定 `key`、原文、译文、前置图标、可选删除按钮。
- `V3TagFlowView`：位于 `app`，负责 Pixiv 数据转换、搜索和标签业务菜单。
- `WitTagStyle`：收藏筛选的手动布局复用此样式，包含/排除仍由业务状态驱动。

| 旧能力 / 业务 | 新入口 |
| --- | --- |
| 自动换行、边距、RTL、左/中/右对齐 | `flexWrap`、`FlexboxLayout.LayoutParams`、`justifyContent` |
| 自定义 View（如固定作者头像） | `setItemViewFactory` 后 `setItems`；内部按钮保留自己的监听 |
| 数据刷新 | `setItems`，相同内容不重建 View |
| 单选、多选、数量上限 | `maxSelectCount`：0 为动作模式，1 为单选，-1 不限，其他正数为上限 |
| 预选、取消、选中通知 | `setSelectedKeys`、`selectedKeys`、`onSelectionChanged` |
| 选中状态恢复 | View 层级保存；支持数据到达前恢复，重排跟随 key，删除条目同步取消选择 |
| 点击、长按、删除 | 独立 item 监听；未消费的长按不额外切换选中态 |
| 搜索输入编辑 | 原正文/× 命中逻辑；重建标签保留编辑器挂载、选区、composing 和键盘状态 |
| 折叠数量、末尾操作 | `maxTags`、`overflowActionText`、`onOverflowClick` |
| 主题变化 | 重新读取 V3Palette；重挂载、配置变化或 `refreshTheme` 刷新相同数据 |

业务入口包括搜索首页、V2 作品与作者页、小说书签、V3 详情、普通小说卡和系列小说卡。
V2 作品的编辑标签改为真实尾部操作；快照仍只允许复制，不提供编辑或在线菜单。
固定标签保留原有插画预览；小说/搜索无插画时保存标签元信息，长按仍可读取官方译名。
打开菜单不预取网络；缺少官方译文时保留主动翻译功能，不伪造译文。

回归测试：`WitTagFlowViewTest`（选择、恢复、删除、布局、主题），`TagChipClickTest`、
`TagEditorStateTest`、`SearchChipEditingTest`（输入框），`AuthorTagMenuTest`
（共享菜单、译文色、固定、作者路由和快照）。覆盖 Android 28/35、日夜、窄屏、RTL 和大字体。
