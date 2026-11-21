package ceui.pixiv.witstudio.widget

/**
 * Common host contract for view groups that capture a background display list and provide
 * live optical refraction/blur to descendant [WitLiquidGlassView]s.
 */
public interface WitLiquidGlassHost {
    public var quality: WitLiquidGlassQuality
    public var isLiveBackdropEnabled: Boolean
}
