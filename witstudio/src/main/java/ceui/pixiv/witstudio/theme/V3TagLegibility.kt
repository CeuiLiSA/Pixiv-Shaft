package ceui.pixiv.witstudio.theme

/**
 * 标签原文辨识度增强的进程级输入。
 *
 * 为什么用全局可变状态而不是构造参数：[V3Palette.from] 是全 app 的取色入口，而标签流是在
 * XML 里 inflate 的（[ceui.pixiv.witstudio.widget.WitTagFlowView] 自己调 `from(context)`），
 * 没有地方逐层传参；witstudio 又不依赖 `:app`，读不到宿主的 Settings。所以由宿主在设置
 * 加载 / 变更时写进来。
 *
 * 这**不是**主题属性耦合 —— [ceui.pixiv.witstudio.WitStudio] 契约里"只从宿主主题读
 * `colorPrimary`"约束的是 `?attr/` 依赖；本对象是纯运行时输入，主题重挂时本模块依然零改动。
 *
 * 值域 0f..1f，0 = 不增强。深浅各一条、互不影响：只想提高深色下的辨识度就只动 [dark]，
 * 浅色模式一点不受影响。
 */
public object V3TagLegibility {

    /** 浅色模式的增强强度，0f = 不增强。 */
    @Volatile
    public var light: Float = 0f

    /** 深色模式的增强强度，0f = 不增强。 */
    @Volatile
    public var dark: Float = 0f

    /** 当前模式该用的强度，读时钳到 0f..1f，写坏了也不会把色值算飞。 */
    @JvmStatic
    public fun boostFor(isDark: Boolean): Float = (if (isDark) dark else light).coerceIn(0f, 1f)

    /** 宿主一次性写入两条强度（来自设置页），写时同样钳到 0f..1f。 */
    @JvmStatic
    public fun setBoosts(lightBoost: Float, darkBoost: Float) {
        light = lightBoost.coerceIn(0f, 1f)
        dark = darkBoost.coerceIn(0f, 1f)
    }
}