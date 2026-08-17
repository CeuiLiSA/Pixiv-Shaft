package ceui.pixiv.witstudio

/**
 * wit studio —— Pixiv-Shaft 的 UI 基建模块。
 *
 * 存在的理由不是复用，而是**切断对 QMUI 的主题依赖**：`AppTheme` 的 parent 一直是
 * `QMUI.Compat.NoActionBar`，QMUI 借此在无人察觉的情况下供给 `textColorPrimary`、
 * `colorAccent`、`windowBackground` 等一批全局属性（且不带 values-night，夜间也是白底），
 * 这层依赖挡着整个 app 迁到 Material3。本模块提供 QMUI 在本项目实际用到的全部能力的
 * V3 / MD3-E 等价物，迁完即可删掉 QMUI 并重挂主题。
 *
 * # 设计语言
 * 形状语汇取 MD3-Expressive：更大的圆角 + 更松的留白。圆角梯度 sm 10 / md 14 / lg 20 /
 * xl 28 / pill 999(dp)；近乎零 elevation，层次靠 0.5dp hairline + 填充对比拉开；
 * 列表用 MD3-E 分段行（外角 20dp、内角 5dp、行距 2dp）。
 *
 * # 主题契约（改本模块任何一行前先读这段）
 * 模块只从宿主主题读**一个**属性：`?attr/colorPrimary`，其余一切自带。具体两条铁律：
 *
 * 1. **禁止硬编码任何强调色。** 所有强调色一律经 [ceui.pixiv.witstudio.theme.V3Palette]
 *    从 `?attr/colorPrimary` 派生，这样 `AppTheme.Index0..9` 十档预设和 `AppTheme.Custom`
 *    自定义 HEX（`CustomThemeColor` 的 ResourcesLoader 运行时覆盖）才能自动跟随。
 *    静态 chrome 色走 `wit_*` 资源，且每个都必须有 `values-night` 孪生。
 *
 * 2. **窗口主题一律用 `parent=""` 的无父 overlay。** `ContextThemeWrapper` 是合并语义
 *    （先 `mTheme.setTo(base.getTheme())` 再 `applyStyle`），所以无父样式只覆盖它显式列出的
 *    window 属性，宿主的 `colorPrimary` 原样存活。若给它挂上 `Theme.AppCompat.Dialog` 之类的
 *    parent，`applyStyle` 会把那个主题的 `colorPrimary` 一起拖进来盖掉宿主的，模块立刻
 *    不再跟随主题档。代价是每个需要的属性都得在 overlay 里显式声明，没有继承可依赖。
 *
 * 这两条合起来的效果：同一份组件代码在迁移期的 AppCompat/QMUI 主题下和迁移后的
 * Material3 主题下都正确，主题重挂那一步本模块**零改动**。
 */
public object WitStudio {

    /**
     * 模块版本。跟 app 版本无关，只在本模块出现破坏性 API 变更时递增，
     * 用于迁移期排查「组件行为不对」到底是哪一版引入的。
     */
    public const val VERSION: String = "0.1.0"
}
