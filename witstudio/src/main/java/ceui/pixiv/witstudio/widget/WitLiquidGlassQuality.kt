package ceui.pixiv.witstudio.widget

/**
 * 液态玻璃渲染效果等级（无 / 标准 / 极致）。
 */
public enum class WitLiquidGlassQuality {
    /** 完全关闭离屏实时采样，使用轻量静态半透明底色与反光描边，零 GPU 额外开销。 */
    NONE,

    /** 实时模糊与透光，关闭色散与复杂折射计算，减少纹理采样开销，兼顾流畅度与通透感。 */
    STANDARD,

    /** 完整物理光学折射、RGB 色散分流（Prism Split）、双向高光与触控果冻流光。 */
    ULTRA;

    public val isLiveEnabled: Boolean get() = this != NONE

    public companion object {
        /** 全局默认质量等级，默认无。 */
        @JvmStatic
        public var defaultQuality: WitLiquidGlassQuality = NONE

        @JvmStatic
        public fun fromOrdinal(ordinal: Int): WitLiquidGlassQuality =
            entries.getOrElse(ordinal) { NONE }
    }
}