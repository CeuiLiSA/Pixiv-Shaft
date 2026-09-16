# V3 设计套件

[先读三分钟接手](../../docs/v3-design-philosophy.md#三分钟接手) → [看组件图鉴](index.html) → [复制页面模板](starter.html)。

本套件复用已确认的 App 推介页的配色、留白、容器与动效关系，补齐通用状态。**Montserrat** 负责英文、数字与品牌文字；中文回退 Noto Sans SC、苹方或系统中文字体。默认正文 14px、关键操作热区 48px；已确认原型的小字不作为生产基线。

## 本地打开

从仓库根目录启动服务，使文档、字体和原型都能访问：

```sh
python3 -m http.server 8766 --bind 127.0.0.1
```

- 图鉴：http://127.0.0.1:8766/mockup/v3-design-system/
- 模板：http://127.0.0.1:8766/mockup/v3-design-system/starter.html
- 已确认页面：http://127.0.0.1:8766/mockup/referral-plan/

无需 npm、构建工具或外部 JS 库。套件使用仓库已有 Montserrat TTF，离线也可展示。直接打开 HTML 也能看样式，但 HTTP 服务更适合验证路径与交互。

## 文件职责

| 文件 | 用途 |
| --- | --- |
| [tokens.css](tokens.css) | 日夜与三套主色、文字、间距、圆角、动效的共享变量 |
| [fonts.css](fonts.css) | 400 / 500 / 600 / 700 / 800 真实 Montserrat 字重，引用 `app/src/main/res/font` |
| [components.css](components.css) | `.v3-*` 布局、Hero、按钮、卡片、进度、分段行、表单、面板等基础样式 |
| [index.html](index.html) / [guide.css](guide.css) | 可切主题、可看状态的组件图鉴；`guide.css` 只用于图鉴排版 |
| [starter.html](starter.html) | 无业务依赖的完整页面骨架，手机/桌面自适应 |
| [demo.js](demo.js) | 图鉴与模板的主题切换、示例状态和弹窗交互；**不提供业务逻辑** |

## 新页面复制方式

在 `mockup/your-page/` 中复制 `starter.html` 为 `index.html`，然后：

1. 将 stylesheet 改为 `../v3-design-system/components.css`。
2. 将 script 改为 `../v3-design-system/demo.js`，或创建自己的业务脚本。生产页面移除所有 `data-*-demo` 演示处理，业务操作不要继续挂 `data-open-dialog` 的通用演示面板。
3. 将“组件图鉴”链接改为 `../v3-design-system/index.html`，“模板复制说明”改为 `../v3-design-system/README.md`；品牌返回链接指向当前 `index.html`。根目录文档链接 `../../docs/...` 在同级新页面中保持有效。
4. 替换标题、说明、类别、指标和操作；管理/设置页面直接删除 Hero，按规范配方复用分段行或列表。
5. 页面私有样式用独立文件加载在 components 之后，不复制全套 tokens、不覆盖全局主题色。需要新通用组件时同步规范和图鉴。

若导出到仓库之外，带上 `tokens.css`、`components.css`、`fonts.css` 和 `fonts.css` 引用的字体文件，更新相对路径并保留字体许可证。不要假定接手环境也有此仓库的 `app/` 路径。

## 字体层级速查

| 用途 | 字号 | 字重 | 颜色 |
| --- | --- | --- | --- |
| Hero 标题 | 28–40px | 800 | `ink`；少量强调可用 `on-tint` |
| 页面标题 | 28–32px | 700 | `ink` |
| 分区标题 | 20px | 700 | `ink` |
| 卡片标题 | 15–16px | 600 | `ink` |
| 正文 | 14–16px | 400 | 主体信息 `ink`，次级说明 `muted` |
| 按钮 | 14px | 600 | 实色按钮 `primary-ink`，浅色按钮 `on-tint` |
| 数字 | 40–55px | 600 | `ink`，等宽数字特性，单位 12–14px / 400 / `muted` |
| 辅助标签 | 11–12px | 400–500 | `muted`；eyebrow 用 `primary` / 700 |

Montserrat 不包含中文，必须保留中文字体回退；不要通过合成加粗假造字体里不存在的字重。Android 已有同名字重资源与 `textMontserrat*` 样式，直接复用。详细字号、行高、对比度与移动端要求以 [设计哲学](../../docs/v3-design-philosophy.md) 为准。

## 预览与验证

[桌面图鉴](guide-desktop.png) · [深色手机图鉴](guide-mobile-dark.png) · [手机页面模板](starter-mobile.png)。

2026-09-08 使用 Chrome / Playwright 检查：

- Montserrat 五档字体实际加载；浏览器渲染数字确认使用本地 `Montserrat SemiBold`，未回退到系统英文字体。
- 三套主色 × 日夜模式 × 10 组文字/背景组合均达到 4.5:1。Hero 说明使用专门的 `on-hero`，避免默认辅助灰在染色背景上对比不足。
- 图鉴主题切换、五种状态预览、弹窗确认和 Escape 返回正常，无 JavaScript 异常或资源请求失败。
- 图鉴 / 模板在 320–1440px 的选定手机、平板、桌面宽度无横向溢出；390px 窗口下 200% CSS 缩放无横向溢出，模板弹窗仍可操作。
- 本地文档、HTML、CSS 与字体引用路径已检查；App 推介页原有领取、审核重提和权益顺延流程回归通过。

以上是 Web 原型验证，不代替 Android 真机字体缩放、TalkBack 或完整无障碍审计。
