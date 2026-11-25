package ceui.pixiv.witstudio.widget

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.view.get
import androidx.core.view.isNotEmpty
import androidx.core.view.size
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackend
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassLoupeShader
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassPhysics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Internal hook allowing [WitLiquidGlassTabBar] to coordinate custom drawing and touch routing
 * inside [WitLiquidGlassView] without exposing internal rendering details in the public API.
 */
internal interface WitLiquidGlassOverlay {
    val isActive: Boolean
    fun onDispatchTouchEvent(event: MotionEvent): Boolean
    fun dispatchDraw(canvas: Canvas, drawChildren: (Canvas) -> Unit)
    fun onWindowVisibilityChanged(visibility: Int)
    fun onDetachedFromWindow()
}

/**
 * Pixel-calibrated iOS 26 Liquid Glass interactive bottom navigation bar controller.
 *
 * Built directly on the shared [WitLiquidGlassPhysics] spring calibration used across all
 * `witstudio` liquid-glass surfaces:
 * - **Press expansion**: The entire outer glass bar swells elastically (`1.000 -> 1.0372`,
 *   `k=310, ζ=0.65`, `+6.8%` peak overshoot at `~225ms`), while the inner resting selection pill
 *   dissolves and inflates into a crystal-clear refractive liquid-glass droplet loupe protruding
 *   beyond the outer bar (`~1.245x` bar height, `1.15x` crisp foreground magnification -> `~1.19x`
 *   total icon zoom).
 * - **Interactive horizontal scrubbing**: Dragging across tabs glides the droplet smoothly with
 *   transform-invariant touch tracking, live pixel-exact accent/unselected color masking,
 *   monotonic curved meniscus bevel refraction & RGB prism dispersion (API 33+ ULTRA),
 *   area-conserving velocity squash-and-stretch (`+16.5%` W / `-18%` H at high speed), subtle
 *   tab-crossing haptics, and elastic rubber-band edge over-drag with wall bulge & outer-bar pull.
 * - **Release settle-back**: Commits the target tab immediately, snaps the droplet smoothly onto
 *   the tab center (`k=164, ζ=0.89`, `~350ms`), contracts the loupe back into the resting pill
 *   (`k=310, ζ=0.79`, `~290ms`), and rebounds the outer bar with its signature `-8.9%` undershoot
 *   (`0.9967`) settling cleanly back to `1.000` (`k=269, ζ=0.61`, `~350ms`).
 */
public class WitLiquidGlassTabBar private constructor(
    private val glassBar: WitLiquidGlassView,
    private val host: TabHostAdapter,
) : WitLiquidGlassOverlay {

    private interface TabHostAdapter {
        val hostView: View
        val isScrollable: Boolean
        val usesVariableTabWidths: Boolean
        val tabCount: Int
        fun selectedIndex(): Int
        fun selectTab(index: Int, reselect: Boolean)
        fun configureForLoupe()
        fun applyTint(maskingMode: Boolean, restingTintList: ColorStateList?, opaqueWhiteTint: ColorStateList)
        fun restoreOnDetach()
        fun readTabGeometry(
            glassBar: WitLiquidGlassView,
            centers: FloatArray,
            widths: FloatArray,
            ids: IntArray,
        ): Boolean
        fun isCachedGeometryExact(
            glassBar: WitLiquidGlassView,
            centers: FloatArray,
            widths: FloatArray,
            ids: IntArray,
            count: Int,
        ): Boolean
        fun scrollX(): Int = 0
        fun maxScrollX(): Int = 0
        fun scrollBy(dx: Int) = Unit
        fun ensureTabVisible(index: Int) = Unit
        fun installObservers(onGeometryChanged: () -> Unit, onScrolled: () -> Unit) = Unit
        fun uninstallObservers() = Unit
    }

    private class BottomNavAdapter(
        private val navigationView: BottomNavigationView,
    ) : TabHostAdapter {
        private val reusableItemViews = ArrayList<View>(5)
        private var savedItemRippleColor: ColorStateList? = null
        private var rippleSaved = false

        override val hostView: View get() = navigationView
        override val isScrollable: Boolean get() = false
        override val usesVariableTabWidths: Boolean get() = false
        override val tabCount: Int get() = navigationView.menu.size

        override fun selectedIndex(): Int {
            val menu = navigationView.menu
            for (i in 0 until menu.size) {
                if (menu[i].isChecked) return i
            }
            return if (menu.isNotEmpty()) 0 else -1
        }

        override fun selectTab(index: Int, reselect: Boolean) {
            val menu = navigationView.menu
            if (index !in 0 until menu.size) return
            navigationView.selectedItemId = menu[index].itemId
        }

        override fun configureForLoupe() {
            if (!rippleSaved) {
                savedItemRippleColor = navigationView.itemRippleColor
                rippleSaved = true
            }
            navigationView.itemBackground = null
            navigationView.itemRippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
            navigationView.isItemActiveIndicatorEnabled = false
            navigationView.clipChildren = false
            navigationView.clipToPadding = false
            for (i in 0 until navigationView.childCount) {
                val child = navigationView.getChildAt(i)
                if (child is ViewGroup) {
                    child.clipChildren = false
                    child.clipToPadding = false
                }
            }
        }

        override fun applyTint(
            maskingMode: Boolean,
            restingTintList: ColorStateList?,
            opaqueWhiteTint: ColorStateList,
        ) {
            val tint = if (maskingMode) opaqueWhiteTint else (restingTintList ?: opaqueWhiteTint)
            val iconTintChanged = navigationView.itemIconTintList !== tint
            if (iconTintChanged) {
                navigationView.itemIconTintList = tint
            }
            if (navigationView.itemTextColor !== tint) {
                navigationView.itemTextColor = tint
            }
            if (iconTintChanged) {
                val menuView = navigationView.getChildAt(0) as? ViewGroup ?: return
                for (i in 0 until menuView.childCount) {
                    menuView.getChildAt(i).refreshDrawableState()
                }
            }
        }

        override fun restoreOnDetach() {
            if (rippleSaved) {
                navigationView.itemRippleColor = savedItemRippleColor
            }
        }

        override fun readTabGeometry(
            glassBar: WitLiquidGlassView,
            centers: FloatArray,
            widths: FloatArray,
            ids: IntArray,
        ): Boolean {
            val menu = navigationView.menu
            val menuCount = menu.size
            if (menuCount <= 0 || centers.size < menuCount) return false
            val menuView = navigationView.getChildAt(0) as? ViewGroup
            val navOffsetX = navigationView.left.toFloat() + (menuView?.left?.toFloat() ?: 0f)
            reusableItemViews.clear()
            if (menuView != null) {
                for (i in 0 until menuView.childCount) {
                    val child = menuView.getChildAt(i)
                    if (child.visibility != View.GONE && child.width > 0) {
                        reusableItemViews += child
                    }
                }
            }
            if (reusableItemViews.size == menuCount) {
                for (i in 0 until menuCount) {
                    ids[i] = menu[i].itemId
                    val itemView = reusableItemViews[i]
                    centers[i] = navOffsetX + (itemView.left + itemView.right) * 0.5f
                    widths[i] = itemView.width.toFloat()
                }
            } else {
                val isRtl = navigationView.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val slot = glassBar.width.toFloat() / menuCount
                for (i in 0 until menuCount) {
                    ids[i] = menu[i].itemId
                    val visualSlot = if (isRtl) (menuCount - 1 - i) else i
                    centers[i] = slot * (visualSlot + 0.5f)
                    widths[i] = slot
                }
            }
            reusableItemViews.clear()
            return true
        }

        override fun isCachedGeometryExact(
            glassBar: WitLiquidGlassView,
            centers: FloatArray,
            widths: FloatArray,
            ids: IntArray,
            count: Int,
        ): Boolean {
            val menu = navigationView.menu
            val menuView = navigationView.getChildAt(0) as? ViewGroup
            val navOffsetX = navigationView.left.toFloat() + (menuView?.left?.toFloat() ?: 0f)
            if (menuView == null) {
                for (i in 0 until count) {
                    if (ids[i] != menu[i].itemId) return false
                }
                return true
            }
            var visibleIndex = 0
            for (i in 0 until menuView.childCount) {
                val child = menuView.getChildAt(i)
                if (child.visibility != View.GONE && child.width > 0) {
                    if (visibleIndex >= count) return false
                    if (ids[visibleIndex] != menu[visibleIndex].itemId) return false
                    val expectedCenter = navOffsetX + (child.left + child.right) * 0.5f
                    if (abs(expectedCenter - centers[visibleIndex]) > 0.01f) return false
                    visibleIndex++
                }
            }
            return visibleIndex == count
        }
    }

    private class TabLayoutAdapter(
        private val tabLayout: TabLayout,
    ) : TabHostAdapter {
        private var savedRippleColor: ColorStateList? = null
        private var savedIndicator: Drawable? = null
        private var savedClipChildren = true
        private var savedClipToPadding = true
        private var savedStripClipChildren = true
        private var savedStripClipToPadding = true
        private var savedState = false
        private var hierarchyListener: ViewGroup.OnHierarchyChangeListener? = null
        private var tabSelectedListener: TabLayout.OnTabSelectedListener? = null

        override val hostView: View get() = tabLayout
        override val isScrollable: Boolean
            get() = tabLayout.tabMode == TabLayout.MODE_SCROLLABLE ||
                tabLayout.tabMode == TabLayout.MODE_AUTO
        override val usesVariableTabWidths: Boolean get() = true
        override val tabCount: Int get() = tabLayout.tabCount

        override fun selectedIndex(): Int {
            val count = tabLayout.tabCount
            if (count <= 0) return -1
            val pos = tabLayout.selectedTabPosition
            return if (pos in 0 until count) pos else 0
        }

        override fun selectTab(index: Int, reselect: Boolean) {
            tabLayout.getTabAt(index)?.select()
        }

        override fun configureForLoupe() {
            val strip = tabLayout.getChildAt(0) as? ViewGroup
            if (!savedState) {
                savedRippleColor = tabLayout.tabRippleColor
                savedIndicator = tabLayout.tabSelectedIndicator
                savedClipChildren = tabLayout.clipChildren
                savedClipToPadding = tabLayout.clipToPadding
                savedStripClipChildren = strip?.clipChildren ?: true
                savedStripClipToPadding = strip?.clipToPadding ?: true
                savedState = true
            }
            tabLayout.setSelectedTabIndicator(null)
            tabLayout.setSelectedTabIndicatorColor(Color.TRANSPARENT)
            tabLayout.tabRippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
            tabLayout.setBackgroundColor(Color.TRANSPARENT)
            // Keep scrollable TabLayout clipped horizontally to its own bounds so scrolled-out tabs
            // don't spill outside the capsule caps; fixed TabLayout stays unclipped for loupe bloom.
            val clip = isScrollable
            tabLayout.clipChildren = clip
            tabLayout.clipToPadding = clip
            strip?.let {
                it.clipChildren = clip
                it.clipToPadding = clip
            }
        }

        override fun applyTint(
            maskingMode: Boolean,
            restingTintList: ColorStateList?,
            opaqueWhiteTint: ColorStateList,
        ) {
            val tint = if (maskingMode) opaqueWhiteTint else (restingTintList ?: opaqueWhiteTint)
            if (tabLayout.tabTextColors !== tint) {
                tabLayout.tabTextColors = tint
            }
        }

        override fun restoreOnDetach() {
            if (savedState) {
                tabLayout.tabRippleColor = savedRippleColor
                tabLayout.setSelectedTabIndicator(savedIndicator)
                tabLayout.clipChildren = savedClipChildren
                tabLayout.clipToPadding = savedClipToPadding
                (tabLayout.getChildAt(0) as? ViewGroup)?.let { strip ->
                    strip.clipChildren = savedStripClipChildren
                    strip.clipToPadding = savedStripClipToPadding
                }
                savedState = false
            }
        }

        override fun scrollX(): Int = tabLayout.scrollX

        override fun maxScrollX(): Int {
            val strip = tabLayout.getChildAt(0) ?: return 0
            val contentW = strip.width + tabLayout.paddingLeft + tabLayout.paddingRight
            return (contentW - tabLayout.width).coerceAtLeast(0)
        }

        override fun scrollBy(dx: Int) {
            if (dx == 0 || !isScrollable) return
            val max = maxScrollX()
            if (max <= 0) return
            val next = (tabLayout.scrollX + dx).coerceIn(0, max)
            if (next != tabLayout.scrollX) {
                tabLayout.scrollTo(next, 0)
            }
        }

        override fun ensureTabVisible(index: Int) {
            if (!isScrollable) return
            val tabView = tabLayout.getTabAt(index)?.view ?: return
            val stripLeft = (tabLayout.getChildAt(0)?.left ?: 0)
            val left = stripLeft + tabView.left
            val right = stripLeft + tabView.right
            val viewW = tabLayout.width
            if (viewW <= 0) return
            val targetScroll = ((left + right) * 0.5f - viewW * 0.5f)
                .roundToInt()
                .coerceIn(0, maxScrollX())
            if (abs(targetScroll - tabLayout.scrollX) > 2) {
                tabLayout.smoothScrollTo(targetScroll, 0)
            }
        }

        override fun installObservers(onGeometryChanged: () -> Unit, onScrolled: () -> Unit) {
            uninstallObservers()
            val strip = tabLayout.getChildAt(0) as? ViewGroup
            hierarchyListener = object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) = onGeometryChanged()
                override fun onChildViewRemoved(parent: View?, child: View?) = onGeometryChanged()
            }.also { strip?.setOnHierarchyChangeListener(it) }
            tabSelectedListener = object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) = onGeometryChanged()
                override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
                override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            }.also { tabLayout.addOnTabSelectedListener(it) }
            tabLayout.setOnScrollChangeListener { _, _, _, _, _ -> onScrolled() }
        }

        override fun uninstallObservers() {
            (tabLayout.getChildAt(0) as? ViewGroup)?.setOnHierarchyChangeListener(null)
            hierarchyListener = null
            tabSelectedListener?.let { tabLayout.removeOnTabSelectedListener(it) }
            tabSelectedListener = null
            tabLayout.setOnScrollChangeListener(null)
        }

        override fun readTabGeometry(
            glassBar: WitLiquidGlassView,
            centers: FloatArray,
            widths: FloatArray,
            ids: IntArray,
        ): Boolean {
            val count = tabLayout.tabCount
            if (count <= 0 || centers.size < count) return false
            val strip = tabLayout.getChildAt(0) as? ViewGroup
            val offsetX = tabLayout.left.toFloat() + (strip?.left?.toFloat() ?: 0f) - tabLayout.scrollX.toFloat()
            var anyMeasured = false
            for (i in 0 until count) {
                val tv = tabLayout.getTabAt(i)?.view
                if (tv != null && tv.width > 0) {
                    anyMeasured = true
                    break
                }
            }
            if (anyMeasured) {
                for (i in 0 until count) {
                    ids[i] = i
                    val tv = tabLayout.getTabAt(i)?.view
                    if (tv != null && tv.width > 0) {
                        centers[i] = offsetX + (tv.left + tv.right) * 0.5f
                        widths[i] = tv.width.toFloat()
                    } else {
                        val fallbackSlot = (glassBar.width.toFloat() - glassBar.paddingLeft - glassBar.paddingRight) / count
                        centers[i] = glassBar.paddingLeft + fallbackSlot * (i + 0.5f)
                        widths[i] = fallbackSlot
                    }
                }
            } else {
                val isRtl = tabLayout.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val innerW = (glassBar.width - glassBar.paddingLeft - glassBar.paddingRight).coerceAtLeast(1).toFloat()
                val slot = innerW / count
                for (i in 0 until count) {
                    ids[i] = i
                    val visualSlot = if (isRtl) (count - 1 - i) else i
                    centers[i] = glassBar.paddingLeft + slot * (visualSlot + 0.5f)
                    widths[i] = slot
                }
            }
            return true
        }

        override fun isCachedGeometryExact(
            glassBar: WitLiquidGlassView,
            centers: FloatArray,
            widths: FloatArray,
            ids: IntArray,
            count: Int,
        ): Boolean {
            if (tabLayout.tabCount != count) return false
            val strip = tabLayout.getChildAt(0) as? ViewGroup
            val offsetX = tabLayout.left.toFloat() + (strip?.left?.toFloat() ?: 0f) - tabLayout.scrollX.toFloat()
            for (i in 0 until count) {
                val tv = tabLayout.getTabAt(i)?.view ?: return false
                if (tv.width <= 0) return false
                val expectedCx = offsetX + (tv.left + tv.right) * 0.5f
                if (abs(expectedCx - centers[i]) > 0.5f || abs(tv.width.toFloat() - widths[i]) > 0.5f) {
                    return false
                }
            }
            return true
        }
    }

    public companion object {
        @JvmStatic
        public fun attach(
            glassBar: WitLiquidGlassView,
            navigationView: BottomNavigationView,
        ): WitLiquidGlassTabBar {
            (glassBar.overlay as? WitLiquidGlassTabBar)?.detach()
            return WitLiquidGlassTabBar(glassBar, BottomNavAdapter(navigationView)).also { controller ->
                glassBar.overlay = controller
            }
        }

        /**
         * Attaches the full iOS 26 Liquid Glass interactive tab bar engine (resting inset glass
         * pill, press bloom, 3D AGSL droplet loupe, horizontal drag scrubbing with squash-stretch
         * and haptics, rubber-band over-drag, and continuous ViewPager swipe following) to a
         * Material [TabLayout] hosted inside [glassBar].
         */
        @JvmStatic
        public fun attach(
            glassBar: WitLiquidGlassView,
            tabLayout: TabLayout,
        ): WitLiquidGlassTabBar {
            (glassBar.overlay as? WitLiquidGlassTabBar)?.detach()
            return WitLiquidGlassTabBar(glassBar, TabLayoutAdapter(tabLayout)).also { controller ->
                glassBar.overlay = controller
            }
        }

        /**
         * Configures [glassBar] with the exact calibrated iOS 26 floating glass bar chrome used by
         * the home bottom navigation bar (`Material.REGULAR`, capsule radius, light-mode subtle
         * bright tint + soft elevation, dark-mode crystal transparent glass, `6dp × 3dp` inner pad).
         */
        @JvmStatic
        public fun applyFloatingGlassChrome(
            glassBar: WitLiquidGlassView,
            quality: WitLiquidGlassQuality,
            isDark: Boolean,
        ) {
            val d = glassBar.resources.displayMetrics.density
            val isLive = quality.isLiveEnabled
            glassBar.quality = quality
            glassBar.material = WitLiquidGlassView.Material.REGULAR
            glassBar.tone = WitLiquidGlassView.Tone.THEME
            glassBar.cornerRadiusDp = Float.POSITIVE_INFINITY
            glassBar.isGlassInteractive = false
            glassBar.tintColor = if (isLive && !isDark) 0x7CF7F8FC else Color.TRANSPARENT
            glassBar.elevation = if (isLive && !isDark) 8f * d else 0f
            glassBar.background = null
            val padH = (DEFAULT_BAR_HORIZONTAL_PADDING_DP * d).roundToInt()
            val padV = if (isLive) (3f * d).roundToInt() else 0
            glassBar.setPadding(padH, padV, padH, padV)
        }

        /** Returns the calibrated iOS 26 resting inset selection pill color for light or dark mode. */
        @JvmStatic
        @ColorInt
        public fun defaultRestingPillColor(isDark: Boolean): Int =
            if (isDark) ColorUtils.setAlphaComponent(Color.WHITE, 44)
            else ColorUtils.setAlphaComponent(0xFF1A1A2E.toInt(), 40)

        // ── Shared calibrated physics constants (delegating to WitLiquidGlassPhysics) ──
        internal const val BAR_PRESS_SCALE: Float = WitLiquidGlassPhysics.WIDE_BAR_PRESS_SCALE
        internal const val BAR_PRESS_SCALE_Y: Float = WitLiquidGlassPhysics.WIDE_BAR_PRESS_SCALE_Y
        internal const val BAR_PRESS_STIFFNESS: Float = WitLiquidGlassPhysics.PRESS_STIFFNESS
        internal const val BAR_PRESS_DAMPING: Float = WitLiquidGlassPhysics.PRESS_DAMPING
        internal const val BAR_RELEASE_STIFFNESS: Float = WitLiquidGlassPhysics.RELEASE_STIFFNESS
        internal const val BAR_RELEASE_DAMPING: Float = WitLiquidGlassPhysics.RELEASE_DAMPING

        internal const val LIFT_PRESS_STIFFNESS: Float = WitLiquidGlassPhysics.LIFT_PRESS_STIFFNESS
        internal const val LIFT_PRESS_DAMPING: Float = WitLiquidGlassPhysics.LIFT_PRESS_DAMPING
        internal const val LIFT_RELEASE_STIFFNESS: Float = WitLiquidGlassPhysics.LIFT_RELEASE_STIFFNESS
        internal const val LIFT_RELEASE_DAMPING: Float = WitLiquidGlassPhysics.LIFT_RELEASE_DAMPING

        internal const val TRACK_DRAG_STIFFNESS: Float = WitLiquidGlassPhysics.TRACK_DRAG_STIFFNESS
        internal const val TRACK_DRAG_DAMPING: Float = WitLiquidGlassPhysics.TRACK_DRAG_DAMPING
        internal const val TRACK_TAP_STIFFNESS: Float = WitLiquidGlassPhysics.TRACK_TAP_STIFFNESS
        internal const val TRACK_TAP_DAMPING: Float = WitLiquidGlassPhysics.TRACK_TAP_DAMPING
        internal const val TRACK_RELEASE_STIFFNESS: Float = WitLiquidGlassPhysics.TRACK_RELEASE_STIFFNESS
        internal const val TRACK_RELEASE_DAMPING: Float = WitLiquidGlassPhysics.TRACK_RELEASE_DAMPING

        internal const val VEL_STRETCH_STIFFNESS: Float = WitLiquidGlassPhysics.VEL_STRETCH_STIFFNESS
        internal const val VEL_STRETCH_DAMPING: Float = WitLiquidGlassPhysics.VEL_STRETCH_DAMPING
        internal const val VEL_NORM_DP_PER_SEC: Float = WitLiquidGlassPhysics.VEL_NORM_DP_PER_SEC
        internal const val VEL_STRETCH_X_MAX: Float = WitLiquidGlassPhysics.VEL_STRETCH_X_MAX
        internal const val VEL_SQUASH_Y_MAX: Float = WitLiquidGlassPhysics.VEL_SQUASH_Y_MAX

        internal const val WALL_SQUISH_STIFFNESS: Float = WitLiquidGlassPhysics.WALL_SQUISH_STIFFNESS
        internal const val WALL_SQUISH_DAMPING: Float = WitLiquidGlassPhysics.WALL_SQUISH_DAMPING
        internal const val MAX_OVER_TRAVEL_DP: Float = WitLiquidGlassPhysics.MAX_OVER_TRAVEL_DP
        internal const val OVER_DRAG_SCALE_DP: Float = WitLiquidGlassPhysics.OVER_DRAG_SCALE_DP
        internal const val WALL_SQUASH_X_MAX: Float = WitLiquidGlassPhysics.WALL_SQUASH_X_MAX
        internal const val WALL_BULGE_Y_MAX: Float = WitLiquidGlassPhysics.WALL_BULGE_Y_MAX
        internal const val BAR_PULL_SHIFT_MAX_DP: Float = WitLiquidGlassPhysics.PULL_MAX_SHIFT_DP
        internal const val BAR_PULL_SHIFT_Y_MAX_DP: Float = WitLiquidGlassPhysics.BAR_PULL_SHIFT_Y_MAX_DP
        internal const val BAR_PULL_STRETCH_X_MAX: Float = WitLiquidGlassPhysics.BAR_PULL_STRETCH_X_MAX

        /**
         * Held droplet height ratio in bar-local coordinates. Combined with [BAR_PRESS_SCALE_Y] (1.040),
         * screen height = 1.135 * 1.040 = 1.180x resting bar height (~74dp on 62.7dp bar, protruding
         * ~4.2dp sleekly above and below the pressed bar to match iOS 26 UITabBar's sleek lozenge).
         */
        internal const val HELD_HEIGHT_LOCAL_RATIO: Float = 1.135f

        /**
         * Held droplet width multiplier over resting pill width in bar-local coordinates.
         * Combined with [BAR_PRESS_SCALE] (1.040), screen width = 1.18 * 1.040 = 1.227x resting pill
         * (~127dp, matching concentric end-cap protrusion at the leftmost & rightmost tabs).
         */
        internal const val HELD_WIDTH_LOCAL_MULT: Float = 1.18f

        internal const val MIN_TAP_BLOOM_MS: Long = WitLiquidGlassPhysics.MIN_TAB_TAP_BLOOM_MS
        internal const val MAX_TAP_BLOOM_MS: Long = WitLiquidGlassPhysics.MAX_TAB_TAP_BLOOM_MS
        internal const val LOUPE_MAGNIFICATION_MAX: Float = 0.14f
        internal const val INNER_BAR_RECESS_SCALE_Y: Float = 0.880f
        internal const val MAX_PHYSICS_SUBSTEP_SEC: Float = WitLiquidGlassPhysics.MAX_PHYSICS_SUBSTEP_SEC
        internal const val MAX_FRAME_DT_SEC: Float = WitLiquidGlassPhysics.MAX_FRAME_DT_SEC

        /** Default outer screen horizontal safety margin (dp) for a centered floating glass tab bar. */
        public const val DEFAULT_SCREEN_MARGIN_H_DP: Int = 16

        /** Default inner horizontal padding (dp) inside the floating glass capsule around tab items. */
        public const val DEFAULT_BAR_HORIZONTAL_PADDING_DP: Int = 6

        /** Compact per-tab baseline width (dp) so 3-4 tab bars float cleanly instead of spanning edge-to-edge. */
        public const val COMPACT_ITEM_WIDTH_DP: Float = 80f

        /** Upper per-tab width cap (dp) when expanding for longer localized labels or larger system fonts. */
        public const val MAX_ITEM_WIDTH_DP: Float = 90f

        /** Horizontal breathing room (dp) added around the widest active tab label inside each slot. */
        public const val LABEL_HORIZONTAL_PADDING_DP: Float = 14f

        private const val ACTIVE_LABEL_TEXT_SIZE_SP: Float = 14f

        /**
         * Measures the widest visible tab title in [navigationView] at active label size (respecting
         * the user's system font scale) so compact floating tab widths never clip localized labels.
         */
        @JvmStatic
        public fun measureMaxTabLabelWidthPx(navigationView: BottomNavigationView): Float {
            val menu = navigationView.menu
            if (menu.size == 0) return 0f
            val dm = navigationView.resources.displayMetrics
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    ACTIVE_LABEL_TEXT_SIZE_SP,
                    dm,
                )
            }
            var maxWidth = 0f
            for (i in 0 until menu.size) {
                val item = menu[i]
                if (!item.isVisible) continue
                val title = item.title
                if (!title.isNullOrEmpty()) {
                    maxWidth = max(maxWidth, paint.measureText(title, 0, title.length))
                }
            }
            return maxWidth
        }

        /**
         * Computes a compact, centered floating bar width (px) from [tabCount] and [maxLabelWidthPx],
         * clamped cleanly within `[availableWidthPx - 2 * horizontalMarginPx]`.
         *
         * Returns [ViewGroup.LayoutParams.MATCH_PARENT] if [tabCount] or [availableWidthPx] is not yet ready.
         */
        @JvmStatic
        @JvmOverloads
        public fun computePreferredBarWidthPx(
            availableWidthPx: Int,
            tabCount: Int,
            maxLabelWidthPx: Float,
            density: Float,
            horizontalMarginPx: Int = (DEFAULT_SCREEN_MARGIN_H_DP * density).roundToInt(),
            barHorizontalPaddingPx: Int = (DEFAULT_BAR_HORIZONTAL_PADDING_DP * density).roundToInt(),
        ): Int {
            if (tabCount <= 0 || availableWidthPx <= 0) {
                return ViewGroup.LayoutParams.MATCH_PARENT
            }
            val safeDensity = density.coerceAtLeast(0.5f)
            val safeMargin = horizontalMarginPx.coerceAtLeast(0)
            val safePadH = barHorizontalPaddingPx.coerceAtLeast(0)
            val maxBarWidth = (availableWidthPx - safeMargin * 2).coerceAtLeast(1)

            val baseItemW = COMPACT_ITEM_WIDTH_DP * safeDensity
            val maxItemW = MAX_ITEM_WIDTH_DP * safeDensity
            val labelItemW = maxLabelWidthPx.coerceAtLeast(0f) + LABEL_HORIZONTAL_PADDING_DP * safeDensity
            val itemW = max(baseItemW, labelItemW).coerceAtMost(maxItemW)
            val desiredBarWidth = (itemW * tabCount + safePadH * 2f).roundToInt()
            return desiredBarWidth.coerceIn(1, maxBarWidth)
        }

        private val CHECKED_ITEM_STATES: Array<IntArray> = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_activated),
            intArrayOf(),
        )

        /** Computes resting pill & held loupe baseline metrics for any bar geometry and tab centers. */
        internal fun computeMetrics(
            barW: Float,
            barH: Float,
            tabCenters: FloatArray,
            density: Float,
        ): BarMetrics {
            if (barW <= 0f || barH <= 0f || tabCenters.isEmpty()) {
                return BarMetrics(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            var minCx = Float.POSITIVE_INFINITY
            var maxCx = Float.NEGATIVE_INFINITY
            for (cx in tabCenters) {
                if (cx < minCx) minCx = cx
                if (cx > maxCx) maxCx = cx
            }
            val n = tabCenters.size
            val slotW = if (n > 1) (maxCx - minCx) / (n - 1) else barW
            val insetV = (barH * 0.075f).coerceIn(3.5f * density, 5.0f * density)
            val restH = (barH - 2f * insetV).coerceAtLeast(min(barH, 32f * density))
            val minEndRoom = min(minCx - insetV, barW - insetV - maxCx).coerceAtLeast(restH * 0.62f)
            val maxRestWByEnd = minEndRoom * 2f
            val preferredRestW = min(max(slotW * 1.08f, restH * 1.62f), restH * 2.05f)
            val restW = preferredRestW.coerceIn(
                min(restH * 1.25f, maxRestWByEnd),
                max(restH * 1.25f, maxRestWByEnd),
            )

            val heldH = barH * HELD_HEIGHT_LOCAL_RATIO
            val protrudeV = (heldH - barH) * 0.5f
            val endSpan = min(minCx, barW - maxCx).coerceAtLeast(barH * 0.5f)
            val concentricEndW = (endSpan + protrudeV * 1.12f) * 2f
            val rawHeldW = max(max(restW * HELD_WIDTH_LOCAL_MULT, heldH * 1.50f), concentricEndW)
            val maxHeldW = min(
                max(restW * HELD_WIDTH_LOCAL_MULT, min(barW * 0.45f, restW * 1.34f)),
                max(restW * 1.12f, concentricEndW),
            )
            val heldW = rawHeldW.coerceAtMost(maxHeldW)

            return BarMetrics(
                barW = barW,
                barH = barH,
                minCx = minCx,
                maxCx = maxCx,
                restW = restW,
                restH = restH,
                heldW = heldW,
                heldH = heldH,
            )
        }

        /** Computes hyperbolic rubber-band over-drag shift and normalized wall pressure `[0..1]`. */
        internal fun computeEdgeOverDrag(
            fingerX: Float,
            minCx: Float,
            maxCx: Float,
            density: Float,
        ): EdgeOverDrag {
            val clamped = fingerX.coerceIn(minCx, maxCx)
            val excess = fingerX - clamped
            if (abs(excess) <= 0.001f) {
                return EdgeOverDrag(clamped, 0f, 0f)
            }
            val maxTravel = MAX_OVER_TRAVEL_DP * density
            val scale = OVER_DRAG_SCALE_DP * density
            val unit = WitLiquidGlassPhysics.rubberBandUnit(excess, scale)
            val shift = unit * maxTravel
            val squish = abs(unit).coerceIn(0f, 1f)
            return EdgeOverDrag(clamped + shift, shift, squish)
        }

        /**
         * Resolves which tab owns horizontal coordinate [x]. For variable-width hosts (Material
         * [TabLayout]), tests each tab's actual `[center - width/2, center + width/2]` interval so
         * tapping near the edge of a wide tab never mis-hits an adjacent narrower tab; outside all
         * intervals, picks the closest tab edge (breaking ties by center distance).
         */
        internal fun resolveTabIndexAt(
            x: Float,
            centers: FloatArray,
            widths: FloatArray,
            useWidths: Boolean,
        ): Int {
            if (centers.isEmpty()) return 0
            val checkIntervals = useWidths && widths.size == centers.size
            var bestIdx = 0
            var bestIntervalDist = Float.POSITIVE_INFINITY
            var bestCenterDist = Float.POSITIVE_INFINITY
            for (i in centers.indices) {
                val centerDist = abs(x - centers[i])
                val intervalDist = if (checkIntervals && widths[i] > 0f) {
                    val halfW = widths[i] * 0.5f
                    max(0f, max((centers[i] - halfW) - x, x - (centers[i] + halfW)))
                } else {
                    centerDist
                }
                if (intervalDist < bestIntervalDist - 0.001f ||
                    (abs(intervalDist - bestIntervalDist) <= 0.001f && centerDist < bestCenterDist)
                ) {
                    bestIntervalDist = intervalDist
                    bestCenterDist = centerDist
                    bestIdx = i
                }
            }
            return bestIdx
        }
    }

    internal data class BarMetrics(
        val barW: Float,
        val barH: Float,
        val minCx: Float,
        val maxCx: Float,
        val restW: Float,
        val restH: Float,
        val heldW: Float,
        val heldH: Float,
    ) {
        val valid: Boolean get() = barW > 0f && barH > 0f && restW > 0f && restH > 0f
    }

    internal data class EdgeOverDrag(
        val targetCx: Float,
        val overShiftPx: Float,
        val wallSquish: Float,
    )

    /**
     * Pure deterministic coupled spring physics engine for the liquid-glass tab bar.
     * Independently unit-testable at any frame rate without Android framework dependencies.
     */
    internal class TabLoupePhysics {
        val barScale = WitLiquidGlassPhysics.SpringState(1f, 0f)
        val barShiftX = WitLiquidGlassPhysics.SpringState(0f, 0f)
        val barShiftY = WitLiquidGlassPhysics.SpringState(0f, 0f)
        val barPullStretchX = WitLiquidGlassPhysics.SpringState(0f, 0f)
        val lift = WitLiquidGlassPhysics.SpringState(0f, 0f)
        val dropCx = WitLiquidGlassPhysics.SpringState(0f, 0f)
        val dropW = WitLiquidGlassPhysics.SpringState(0f, 0f)
        val velocityStretch = WitLiquidGlassPhysics.SpringState(0f, 0f)
        val wallSquish = WitLiquidGlassPhysics.SpringState(0f, 0f)

        var pressed: Boolean = false
        var dragging: Boolean = false
        var targetCx: Float = 0f
        var targetW: Float = 0f
        var targetBarShiftX: Float = 0f
        var targetBarShiftY: Float = 0f
        var targetBarPullStretchX: Float = 0f
        var targetWallSquish: Float = 0f
        var initialized: Boolean = false

        fun snapToRest(cx: Float, restWidth: Float = 0f) {
            barScale.snap(1f)
            barShiftX.snap(0f)
            barShiftY.snap(0f)
            barPullStretchX.snap(0f)
            lift.snap(0f)
            dropCx.snap(cx)
            dropW.snap(restWidth)
            velocityStretch.snap(0f)
            wallSquish.snap(0f)
            targetCx = cx
            targetW = restWidth
            targetBarShiftX = 0f
            targetBarShiftY = 0f
            targetBarPullStretchX = 0f
            targetWallSquish = 0f
            pressed = false
            dragging = false
            initialized = true
        }

        fun step(dtSec: Float, density: Float): Boolean {
            val totalDt = dtSec.coerceIn(0f, MAX_FRAME_DT_SEC)
            if (totalDt <= 0f) return !isSettled()

            val steps = max(1, (totalDt / MAX_PHYSICS_SUBSTEP_SEC).toInt() + 1)
            val subDt = totalDt / steps
            val safeDensity = density.coerceAtLeast(0.5f)

            val barTargetScale = if (pressed) BAR_PRESS_SCALE else 1f
            val barStiff = if (pressed) BAR_PRESS_STIFFNESS else BAR_RELEASE_STIFFNESS
            val barDamp = if (pressed) BAR_PRESS_DAMPING else BAR_RELEASE_DAMPING

            val liftTarget = if (pressed) 1f else 0f
            val liftStiff = if (pressed) LIFT_PRESS_STIFFNESS else LIFT_RELEASE_STIFFNESS
            val liftDamp = if (pressed) LIFT_PRESS_DAMPING else LIFT_RELEASE_DAMPING

            val cxStiff = when {
                dragging -> TRACK_DRAG_STIFFNESS
                pressed -> TRACK_TAP_STIFFNESS
                else -> TRACK_RELEASE_STIFFNESS
            }
            val cxDamp = when {
                dragging -> TRACK_DRAG_DAMPING
                pressed -> TRACK_TAP_DAMPING
                else -> TRACK_RELEASE_DAMPING
            }

            val pullStiff = if (pressed) WitLiquidGlassPhysics.PULL_ACTIVE_STIFFNESS else BAR_RELEASE_STIFFNESS
            val pullDamp = if (pressed) WitLiquidGlassPhysics.PULL_ACTIVE_DAMPING else BAR_RELEASE_DAMPING
            val effShiftX = if (pressed) targetBarShiftX else 0f
            val effShiftY = if (pressed) targetBarShiftY else 0f
            val effPullStretchX = if (pressed) targetBarPullStretchX else 0f
            val effWallSquish = if (pressed) targetWallSquish else 0f

            repeat(steps) {
                barScale.step(barTargetScale, barStiff, barDamp, subDt)
                barShiftX.step(effShiftX, pullStiff, pullDamp, subDt)
                barShiftY.step(effShiftY, pullStiff, pullDamp, subDt)
                barPullStretchX.step(effPullStretchX, pullStiff, pullDamp, subDt)
                lift.step(liftTarget, liftStiff, liftDamp, subDt)
                dropCx.step(targetCx, cxStiff, cxDamp, subDt)
                if (targetW > 0f) {
                    if (dropW.value <= 0f) {
                        dropW.snap(targetW)
                    } else {
                        dropW.step(targetW, cxStiff, cxDamp, subDt)
                    }
                }

                val vxDpPerSec = abs(dropCx.velocity) / safeDensity
                val velTarget = if (lift.value > 0.08f) {
                    (vxDpPerSec / VEL_NORM_DP_PER_SEC).coerceIn(0f, 1f)
                } else {
                    0f
                }
                velocityStretch.step(velTarget, VEL_STRETCH_STIFFNESS, VEL_STRETCH_DAMPING, subDt)
                wallSquish.step(effWallSquish, WALL_SQUISH_STIFFNESS, WALL_SQUISH_DAMPING, subDt)
            }

            if (isSettled()) {
                barScale.snap(barTargetScale)
                barShiftX.snap(effShiftX)
                barShiftY.snap(effShiftY)
                barPullStretchX.snap(effPullStretchX)
                lift.snap(liftTarget)
                dropCx.snap(targetCx)
                if (targetW > 0f) dropW.snap(targetW)
                velocityStretch.snap(0f)
                wallSquish.snap(effWallSquish)
                return false
            }
            return true
        }

        fun isSettled(): Boolean {
            val barTargetScale = if (pressed) BAR_PRESS_SCALE else 1f
            val liftTarget = if (pressed) 1f else 0f
            val effShiftX = if (pressed) targetBarShiftX else 0f
            val effShiftY = if (pressed) targetBarShiftY else 0f
            val effPullStretchX = if (pressed) targetBarPullStretchX else 0f
            val effWallSquish = if (pressed) targetWallSquish else 0f
            return barScale.isSettled(barTargetScale, 0.0009f, 0.012f) &&
                barShiftX.isSettled(effShiftX, 0.25f, 2.5f) &&
                barShiftY.isSettled(effShiftY, 0.25f, 2.5f) &&
                barPullStretchX.isSettled(effPullStretchX, 0.0006f, 0.006f) &&
                lift.isSettled(liftTarget, 0.004f, 0.035f) &&
                dropCx.isSettled(targetCx, 0.35f, 4.0f) &&
                (targetW <= 0f || dropW.isSettled(targetW, 0.35f, 4.0f)) &&
                velocityStretch.isSettled(0f, 0.005f, 0.04f) &&
                wallSquish.isSettled(effWallSquish, 0.005f, 0.04f)
        }

        fun evaluateGeometry(metrics: BarMetrics): DropletGeometry {
            val out = MutableDropletGeometry()
            evaluateGeometryInto(metrics, out)
            return DropletGeometry(
                cx = out.cx,
                cy = out.cy,
                width = out.width,
                height = out.height,
                lift = out.lift,
                velocityStretch = out.velocityStretch,
                barScaleX = out.barScaleX,
                barScaleY = out.barScaleY,
                barShiftX = out.barShiftX,
                barShiftY = out.barShiftY,
            )
        }

        fun evaluateGeometryInto(metrics: BarMetrics, out: MutableDropletGeometry) {
            val l = lift.value
            val lPos = l.coerceIn(0f, 1.15f)
            val activeW = l.coerceIn(0f, 1f)
            val vStretch = velocityStretch.value.coerceIn(WitLiquidGlassPhysics.VEL_REBOUND_MIN, 1.18f)
            val wSquish = wallSquish.value.coerceIn(WitLiquidGlassPhysics.WALL_SQUISH_REBOUND_MIN, 1.15f)

            val restW = if (targetW > 0f && dropW.value > 0.5f) dropW.value else metrics.restW
            val heldW = if (targetW > 0f && dropW.value > 0.5f) {
                val protrudeV = (metrics.heldH - metrics.barH).coerceAtLeast(0f) * 0.5f
                max(restW + protrudeV * 2.24f, metrics.heldH * 1.35f)
            } else {
                metrics.heldW
            }

            val baseW = (restW + (heldW - restW) * lPos)
                .coerceAtLeast(metrics.restH)
            val baseH = (metrics.restH + (metrics.heldH - metrics.restH) * lPos)
                .coerceAtLeast(metrics.restH * 0.85f)

            val velScaleX = 1f + VEL_STRETCH_X_MAX * vStretch
            val velScaleY = 1f - VEL_SQUASH_Y_MAX * vStretch
            val wallScaleX = 1f - WALL_SQUASH_X_MAX * wSquish
            val wallScaleY = 1f + WALL_BULGE_Y_MAX * wSquish

            val curW = baseW * (1f + (velScaleX * wallScaleX - 1f) * activeW)
            val curH = baseH * (1f + (velScaleY * wallScaleY - 1f) * activeW)
            val clampedW = max(curW, curH * 1.10f)

            val barDeltaX = barScale.value - 1f
            val yRatio = (BAR_PRESS_SCALE_Y - 1f) / (BAR_PRESS_SCALE - 1f)
            val barScaleYVal = 1f + barDeltaX * yRatio

            out.cx = dropCx.value
            out.cy = metrics.barH * 0.5f
            out.width = clampedW
            out.height = curH
            out.lift = activeW
            out.velocityStretch = vStretch * activeW
            out.barScaleX = barScale.value + barPullStretchX.value
            out.barScaleY = barScaleYVal
            out.barShiftX = barShiftX.value
            out.barShiftY = barShiftY.value
        }
    }

    internal class MutableDropletGeometry(
        var cx: Float = 0f,
        var cy: Float = 0f,
        var width: Float = 0f,
        var height: Float = 0f,
        var lift: Float = 0f,
        var velocityStretch: Float = 0f,
        var barScaleX: Float = 1f,
        var barScaleY: Float = 1f,
        var barShiftX: Float = 0f,
        var barShiftY: Float = 0f,
    )

    internal data class DropletGeometry(
        val cx: Float,
        val cy: Float,
        val width: Float,
        val height: Float,
        val lift: Float,
        val velocityStretch: Float,
        val barScaleX: Float,
        val barScaleY: Float,
        val barShiftX: Float,
        val barShiftY: Float,
    )

    private val physics = TabLoupePhysics()
    private val density: Float get() = glassBar.resources.displayMetrics.density
    private val touchSlop: Float = ViewConfiguration.get(glassBar.context).scaledTouchSlop.toFloat()

    private var enabledFlag = false
    private var isDarkTheme = false
    @ColorInt private var selectedColor: Int = Color.BLUE
    @ColorInt private var unselectedColor: Int = Color.WHITE
    @ColorInt private var restingPillColor: Int = 0xD8101828.toInt()

    private var tabCenters = FloatArray(0)
    private var tabWidths = FloatArray(0)
    private var tabMenuIds = IntArray(0)
    private var lastKnownSelectedIndex = -1
    private var metrics = BarMetrics(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private var geometryDirty = true
    private var cachedBarW = 0f
    private var cachedBarH = 0f
    private var cachedDensity = 0f
    private var cachedLayoutDir = -1
    private var cachedScrollX = Int.MIN_VALUE
    private val scratchGeometry = MutableDropletGeometry()

    // Active pointer tracking in transform-invariant bar coordinates
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastMoveX = 0f
    private var downTimeMs = 0L
    private var dragGrabOffsetX = 0f
    private var initialSelectedIndex = -1
    private var touchedTabIndex = -1
    private var currentHoverIndex = -1
    private var didDrag = false
    private var crossedOtherTab = false
    private var committingSelection = false
    private var pageScrollTracking = false
    private var pendingReleaseAtMs = 0L
    private var appliedBarShiftX = 0f
    private var appliedBarShiftY = 0f

    private var frameLoopRunning = false
    private var lastFrameUptimeMs = 0L

    // Drawing caches (zero per-frame allocations)
    private val restingPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val restingSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val restingRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val unselectedLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackGlassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fallbackBaseRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fallbackRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fallbackInnerBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dropletRect = RectF()
    private val dropletPath = Path()
    private val innerBarRect = RectF()
    private val innerBarPath = Path()
    private val layerBounds = RectF()
    private val selectedLayerBounds = RectF()
    private val sceneBoundsLocal = RectF()
    private val restingShaderMatrix = Matrix()
    private var restingSheenShader: LinearGradient? = null
    private var restingRimShader: LinearGradient? = null
    private var restingShaderDark: Boolean? = null
    private val fallbackRimMatrix = Matrix()
    private var fallbackRimShader: LinearGradient? = null
    private var fallbackRimDark: Boolean? = null
    private val opaqueWhiteTint: ColorStateList by lazy(LazyThreadSafetyMode.NONE) {
        // Keep isStateful() == true even during masking mode so Android ImageView.drawableStateChanged()
        // never skips Drawable.setState() when selectedItemId changes mid-gesture.
        ColorStateList(
            CHECKED_ITEM_STATES,
            intArrayOf(Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE),
        )
    }
    private var restingTintList: ColorStateList? = null
    private var maskingMode = false
    private var loupeShader: WitLiquidGlassLoupeShader? = null

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        geometryDirty = true
        if (enabledFlag) {
            refreshTabGeometry(snapIfUninitialized = true)
            glassBar.invalidate()
        }
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            frameLoopRunning = false
            if (!enabledFlag || !glassBar.isAttachedToWindow) {
                resetVisualState()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (pendingReleaseAtMs > 0L) {
                val minReached = now >= downTimeMs + MIN_TAP_BLOOM_MS
                val arrivedAtTarget = metrics.valid &&
                    abs(physics.dropCx.value - physics.targetCx) <= metrics.restW * 0.24f
                if (now >= pendingReleaseAtMs || (minReached && arrivedAtTarget)) {
                    pendingReleaseAtMs = 0L
                    beginReleaseSettle()
                }
            }
            val dtSec = if (lastFrameUptimeMs > 0L) {
                ((now - lastFrameUptimeMs).coerceAtLeast(1L)) / 1000f
            } else {
                1f / 60f
            }
            lastFrameUptimeMs = now

            // Auto-pan scrollable TabLayout smoothly while finger holds near either capsule edge.
            val edgePanActive = maybeAutoPanScrollableHost(dtSec)
            val keepRunning = physics.step(dtSec, density) || pendingReleaseAtMs > 0L || edgePanActive
            applyOuterBarTransform()

            if (keepRunning) {
                if (!maskingMode) {
                    setMaskingMode(true)
                }
                glassBar.invalidate()
                scheduleNextFrame()
            } else {
                lastFrameUptimeMs = 0L
                val settledAtRest = !physics.pressed &&
                    physics.lift.value <= 0.002f &&
                    pendingReleaseAtMs == 0L &&
                    activePointerId == MotionEvent.INVALID_POINTER_ID
                if (settledAtRest) {
                    val checkedIdx = resolveCheckedMenuIndex()
                    if (checkedIdx in tabCenters.indices &&
                        abs(physics.dropCx.value - tabCenters[checkedIdx]) <= 1.0f
                    ) {
                        setMaskingMode(false)
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        loupeShader?.clear()
                    }
                }
                glassBar.invalidate()
            }
        }
    }

    init {
        host.hostView.addOnLayoutChangeListener(layoutListener)
        glassBar.addOnLayoutChangeListener(layoutListener)
    }

    override val isActive: Boolean
        get() = enabledFlag

    /** Enables or disables the floating liquid-glass tab loupe behavior cleanly. */
    public var isEnabled: Boolean
        get() = enabledFlag
        set(value) {
            if (enabledFlag == value) return
            enabledFlag = value
            if (value) {
                glassBar.isGlassInteractive = false
                glassBar.clipChildren = false
                glassBar.clipToPadding = false
                geometryDirty = true
                configureHostForLoupe()
                host.installObservers(
                    onGeometryChanged = {
                        geometryDirty = true
                        if (enabledFlag) {
                            refreshTabGeometry(snapIfUninitialized = true)
                            glassBar.invalidate()
                        }
                    },
                    onScrolled = {
                        geometryDirty = true
                        if (enabledFlag) {
                            refreshTabGeometry(snapIfUninitialized = true)
                            if (activePointerId == MotionEvent.INVALID_POINTER_ID && !pageScrollTracking) {
                                val checkedIdx = resolveCheckedMenuIndex()
                                if (checkedIdx in tabCenters.indices) {
                                    physics.targetCx = tabCenters[checkedIdx]
                                    physics.targetW = targetRestWidthForIndex(checkedIdx)
                                    if (!frameLoopRunning && !physics.pressed && pendingReleaseAtMs == 0L) {
                                        physics.dropCx.snap(physics.targetCx)
                                        if (physics.targetW > 0f) physics.dropW.snap(physics.targetW)
                                    }
                                }
                            }
                            glassBar.invalidate()
                        }
                    },
                )
                refreshTabGeometry(snapIfUninitialized = true)
                glassBar.invalidate()
            } else {
                cancelAndReset()
            }
        }

    /**
     * Positions [glassBar] vertically without clobbering any active elastic vertical drag pull
     * applied by [WitLiquidGlassTabBar]. Use this instead of setting `glassBar.translationY` directly
     * when the floating tab bar also translates with scroll/AppBar collapse.
     */
    public fun setBaseTranslationY(baseY: Float) {
        val target = baseY + appliedBarShiftY
        if (glassBar.translationY != target) {
            glassBar.translationY = target
        }
    }

    /** Updates theme-derived colors for selected icons/text, unselected icons/text, and resting pill. */
    public fun applyThemeColors(
        @ColorInt selectedColor: Int,
        @ColorInt unselectedColor: Int,
        @ColorInt restingPillColor: Int,
        isDark: Boolean,
    ) {
        this.selectedColor = selectedColor
        this.unselectedColor = unselectedColor
        this.restingPillColor = restingPillColor
        this.isDarkTheme = isDark

        val opaqueSelected = ColorUtils.setAlphaComponent(selectedColor, 255)
        selectedLayerPaint.colorFilter = PorterDuffColorFilter(opaqueSelected, PorterDuff.Mode.SRC_IN)
        selectedLayerPaint.alpha = Color.alpha(selectedColor)

        val opaqueUnselected = ColorUtils.setAlphaComponent(unselectedColor, 255)
        unselectedLayerPaint.colorFilter = PorterDuffColorFilter(opaqueUnselected, PorterDuff.Mode.SRC_IN)
        unselectedLayerPaint.alpha = Color.alpha(unselectedColor)

        restingPillPaint.color = restingPillColor
        restingTintList = ColorStateList(
            CHECKED_ITEM_STATES,
            intArrayOf(selectedColor, selectedColor, selectedColor, unselectedColor),
        )

        if (enabledFlag) {
            configureHostForLoupe()
            glassBar.invalidate()
        }
    }

    /**
     * Continuously tracks a `ViewPager` / `ViewPager2` horizontal swipe (`position` +
     * `positionOffset` in `[0, 1]`), sliding the inset frosted glass pill 1:1 between tabs with
     * live pixel-exact selected/unselected color masking.
     */
    public fun syncPageScroll(position: Int, positionOffset: Float) {
        if (!enabledFlag) return
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || committingSelection ||
            pendingReleaseAtMs > 0L || physics.lift.value > 0.08f
        ) {
            return
        }
        refreshTabGeometry(snapIfUninitialized = true)
        if (tabCenters.isEmpty() || position !in tabCenters.indices) return
        val clampedOffset = positionOffset.coerceIn(0f, 1f)
        val nextPos = (position + 1).coerceAtMost(tabCenters.lastIndex)
        val cx = tabCenters[position] + (tabCenters[nextPos] - tabCenters[position]) * clampedOffset
        val w0 = targetRestWidthForIndex(position)
        val w1 = targetRestWidthForIndex(nextPos)
        val w = w0 + (w1 - w0) * clampedOffset
        val midSwipe = clampedOffset > 0.002f && clampedOffset < 0.998f
        pageScrollTracking = midSwipe
        physics.targetCx = cx
        physics.dropCx.snap(cx)
        if (w > 0f) {
            physics.targetW = w
            physics.dropW.snap(w)
        }
        physics.pressed = false
        physics.dragging = false
        setMaskingMode(midSwipe)
        glassBar.invalidate()
    }

    /** Synchronizes the selection pill position after external tab selection (e.g., ViewPager swipe). */
    @JvmOverloads
    public fun syncSelection(animated: Boolean = true) {
        if (!enabledFlag) return
        pageScrollTracking = false
        geometryDirty = true
        refreshTabGeometry(snapIfUninitialized = false)
        val checkedIdx = resolveCheckedMenuIndex()
        if (checkedIdx < 0 || checkedIdx >= tabCenters.size) return
        lastKnownSelectedIndex = checkedIdx
        if (activePointerId != MotionEvent.INVALID_POINTER_ID || committingSelection) return

        val targetX = tabCenters[checkedIdx]
        val targetWidth = targetRestWidthForIndex(checkedIdx)
        val loupeAnimating = pendingReleaseAtMs > 0L || physics.pressed || physics.lift.value > 0.002f
        if (loupeAnimating) {
            val moved = abs(physics.targetCx - targetX) > 0.5f ||
                (targetWidth > 0f && abs(physics.targetW - targetWidth) > 0.5f)
            if (moved) {
                physics.targetCx = targetX
                physics.targetW = targetWidth
                startFrameLoop()
            }
            return
        }
        if (!physics.initialized || !animated || !motionEnabled()) {
            physics.snapToRest(targetX, targetWidth)
            applyOuterBarTransform()
            setMaskingMode(false)
            glassBar.invalidate()
            return
        }
        if (abs(physics.targetCx - targetX) > 0.5f || abs(physics.dropCx.value - targetX) > 0.5f ||
            (targetWidth > 0f && abs(physics.dropW.value - targetWidth) > 0.5f)
        ) {
            physics.targetCx = targetX
            physics.targetW = targetWidth
            physics.pressed = false
            physics.dragging = false
            setMaskingMode(true)
            startFrameLoop()
        } else {
            setMaskingMode(false)
            glassBar.invalidate()
        }
    }

    /** Detaches listeners, cancels running physics, and releases offscreen GPU nodes. */
    public fun detach() {
        cancelAndReset()
        host.hostView.removeOnLayoutChangeListener(layoutListener)
        glassBar.removeOnLayoutChangeListener(layoutListener)
        if (glassBar.overlay === this) {
            glassBar.overlay = null
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility == View.VISIBLE) return
        glassBar.removeCallbacks(frameRunnable)
        frameLoopRunning = false
        lastFrameUptimeMs = 0L
        activePointerId = MotionEvent.INVALID_POINTER_ID
        pendingReleaseAtMs = 0L
        pageScrollTracking = false
        if (enabledFlag) {
            if (tabCenters.isNotEmpty()) {
                val checkedIdx = resolveCheckedMenuIndex().coerceIn(0, tabCenters.lastIndex)
                lastKnownSelectedIndex = checkedIdx
                physics.snapToRest(tabCenters[checkedIdx], targetRestWidthForIndex(checkedIdx))
            } else {
                physics.initialized = false
            }
            applyOuterBarTransform()
            setMaskingMode(false)
        } else {
            resetVisualState()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            loupeShader?.clear()
        }
    }

    override fun onDetachedFromWindow() {
        pauseForWindowDetach()
    }

    /**
     * Converts view-local touch coordinates back into unscaled bar layout space so scaling or
     * shifting [glassBar] during press/over-drag never creates feedback drift on a stationary finger.
     */
    private fun toUnscaledBarX(localX: Float): Float =
        glassBar.pivotX + (localX - glassBar.pivotX) * glassBar.scaleX + appliedBarShiftX

    private fun toUnscaledBarY(localY: Float): Float =
        glassBar.pivotY + (localY - glassBar.pivotY) * glassBar.scaleY + appliedBarShiftY

    override fun onDispatchTouchEvent(event: MotionEvent): Boolean {
        if (!enabledFlag || !glassBar.isEnabled) return false
        refreshTabGeometry(snapIfUninitialized = true)
        if (tabCenters.isEmpty() || !metrics.valid) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                pendingReleaseAtMs = 0L
                pageScrollTracking = false
                downX = toUnscaledBarX(event.x)
                downY = toUnscaledBarY(event.y)
                lastMoveX = downX
                downTimeMs = SystemClock.uptimeMillis()
                didDrag = false
                crossedOtherTab = false

                initialSelectedIndex = resolveCheckedMenuIndex().coerceIn(0, tabCenters.lastIndex)
                lastKnownSelectedIndex = initialSelectedIndex
                touchedTabIndex = nearestTabIndex(downX)
                currentHoverIndex = touchedTabIndex

                // Keep DrawerLayout / ViewPager from hijacking horizontal scrubbing across the tab bar.
                glassBar.parent?.requestDisallowInterceptTouchEvent(true)

                if (!physics.initialized) {
                    physics.snapToRest(
                        tabCenters[initialSelectedIndex],
                        targetRestWidthForIndex(initialSelectedIndex),
                    )
                }

                if (!motionEnabled()) {
                    return true
                }

                physics.pressed = true
                physics.dragging = false
                physics.targetBarShiftX = 0f
                physics.targetBarShiftY = 0f
                physics.targetBarPullStretchX = 0f
                physics.targetWallSquish = 0f

                if (touchedTabIndex == initialSelectedIndex) {
                    // Preserve resting center on stationary press; smoothly blend off-center grab when dragging.
                    dragGrabOffsetX = physics.dropCx.value - downX
                    physics.targetCx = tabCenters[initialSelectedIndex]
                    physics.targetW = targetRestWidthForIndex(initialSelectedIndex)
                } else {
                    // Pressing another tab launches the liquid glass droplet smoothly toward it immediately.
                    dragGrabOffsetX = tabCenters[touchedTabIndex] - downX
                    physics.targetCx = tabCenters[touchedTabIndex]
                    physics.targetW = targetRestWidthForIndex(touchedTabIndex)
                }
                // Prime frame 0 when waking from rest so press bloom & loupe warmup start on the touch-down frame.
                if (!frameLoopRunning) {
                    physics.step(1f / 120f, density)
                    applyOuterBarTransform()
                }
                setMaskingMode(true)
                glassBar.invalidate()
                startFrameLoop()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val x = toUnscaledBarX(event.getX(idx))
                val y = toUnscaledBarY(event.getY(idx))
                lastMoveX = x
                val dx = x - downX
                val dy = y - downY

                // Trigger horizontal scrub only on horizontal intent so small vertical thumb roll never
                // suppresses same-tab tap refresh or vertical pull-away cancel.
                if (!didDrag && abs(dx) > touchSlop * 0.55f) {
                    didDrag = true
                }

                if (motionEnabled()) {
                    val vertDeadzone = 6f * density
                    val effDy = when {
                        dy > vertDeadzone -> dy - vertDeadzone
                        dy < -vertDeadzone -> dy + vertDeadzone
                        else -> 0f
                    }
                    val nextShiftY = WitLiquidGlassPhysics.rubberBandUnit(effDy, 48f * density) *
                        BAR_PULL_SHIFT_Y_MAX_DP * density
                    if (abs(nextShiftY - physics.targetBarShiftY) > 0.05f) {
                        physics.targetBarShiftY = nextShiftY
                        startFrameLoop()
                    }
                }

                if (didDrag && motionEnabled()) {
                    physics.dragging = true
                    val travelNorm = (abs(dx) / (44f * density)).coerceIn(0f, 1f)
                    val desiredFingerX = x + dragGrabOffsetX * (1f - travelNorm)
                    val clampMin = visibleDragMinCx()
                    val clampMax = visibleDragMaxCx()
                    val edge = computeEdgeOverDrag(desiredFingerX, clampMin, clampMax, density)

                    physics.targetCx = edge.targetCx
                    val canPanLeft = host.isScrollable && host.scrollX() > 0 && edge.overShiftPx < 0f
                    val canPanRight = host.isScrollable && host.scrollX() < host.maxScrollX() && edge.overShiftPx > 0f
                    val hitWall = !canPanLeft && !canPanRight
                    val effSquish = if (hitWall) edge.wallSquish else 0f
                    physics.targetWallSquish = effSquish
                    val pullSign = sign(edge.overShiftPx)
                    physics.targetBarShiftX = pullSign * BAR_PULL_SHIFT_MAX_DP * density * effSquish
                    physics.targetBarPullStretchX = BAR_PULL_STRETCH_X_MAX * effSquish

                    val hover = nearestTabIndex(edge.targetCx)
                    physics.targetW = targetRestWidthForIndex(hover)
                    if (hover != currentHoverIndex) {
                        currentHoverIndex = hover
                        if (hover != initialSelectedIndex) {
                            crossedOtherTab = true
                        }
                        performTabBoundaryHaptic()
                    }
                    setMaskingMode(true)
                    startFrameLoop()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == activePointerId) {
                    finishGesture(event, cancelled = false)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                finishGesture(event, cancelled = false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                finishGesture(event, cancelled = true)
                return true
            }
        }
        return activePointerId != MotionEvent.INVALID_POINTER_ID
    }

    private fun finishGesture(event: MotionEvent, cancelled: Boolean) {
        glassBar.parent?.requestDisallowInterceptTouchEvent(false)
        val idx = event.findPointerIndex(activePointerId)
        val upX = if (idx >= 0) toUnscaledBarX(event.getX(idx)) else downX
        val upY = if (idx >= 0) toUnscaledBarY(event.getY(idx)) else downY
        activePointerId = MotionEvent.INVALID_POINTER_ID

        val farVerticalCancel = !didDrag && abs(upY - metrics.barH * 0.5f) > metrics.barH * 2.4f
        val targetIndex = when {
            cancelled || farVerticalCancel -> initialSelectedIndex.coerceIn(0, tabCenters.lastIndex)
            didDrag -> nearestTabIndex(physics.targetCx)
            else -> nearestTabIndex(upX)
        }

        // Commit tab selection immediately on ACTION_UP with zero navigation delay.
        var switchedTab = false
        if (!cancelled && !farVerticalCancel && targetIndex in tabCenters.indices) {
            val shouldReselectSameTab =
                targetIndex == initialSelectedIndex && !didDrag && !crossedOtherTab
            if (targetIndex != initialSelectedIndex || shouldReselectSameTab) {
                switchedTab = targetIndex != initialSelectedIndex
                committingSelection = true
                try {
                    host.selectTab(targetIndex, reselect = shouldReselectSameTab)
                } finally {
                    committingSelection = false
                }
            }
        }

        val clampedIdx = targetIndex.coerceIn(0, tabCenters.lastIndex)
        if (!switchedTab) {
            host.ensureTabVisible(clampedIdx)
        }
        val finalCx = tabCenters[clampedIdx]
        val finalW = targetRestWidthForIndex(clampedIdx)
        lastKnownSelectedIndex = clampedIdx

        if (!motionEnabled()) {
            physics.snapToRest(finalCx, finalW)
            applyOuterBarTransform()
            glassBar.invalidate()
            return
        }

        physics.dragging = false
        physics.targetCx = finalCx
        physics.targetW = finalW
        physics.targetBarShiftX = 0f
        physics.targetBarShiftY = 0f
        physics.targetBarPullStretchX = 0f
        physics.targetWallSquish = 0f

        val tabSpan = abs(targetIndex - initialSelectedIndex)
        val tapBloomMs = if (!didDrag && tabSpan > 0) {
            (MIN_TAP_BLOOM_MS + tabSpan * 26L).coerceAtMost(MAX_TAP_BLOOM_MS)
        } else {
            MIN_TAP_BLOOM_MS
        }
        val elapsedMs = SystemClock.uptimeMillis() - downTimeMs
        val needsDistanceBloom = !didDrag && tabSpan > 0 &&
            abs(physics.dropCx.value - finalCx) > metrics.restW * 0.24f
        if (!cancelled && elapsedMs < tapBloomMs && (physics.lift.value < 0.65f || needsDistanceBloom)) {
            physics.pressed = true
            pendingReleaseAtMs = downTimeMs + tapBloomMs
        } else {
            beginReleaseSettle()
        }
        startFrameLoop()
    }

    private fun beginReleaseSettle() {
        physics.pressed = false
        physics.dragging = false
        physics.targetBarShiftX = 0f
        physics.targetBarShiftY = 0f
        physics.targetBarPullStretchX = 0f
        physics.targetWallSquish = 0f
    }

    override fun dispatchDraw(canvas: Canvas, drawChildren: (Canvas) -> Unit) {
        refreshTabGeometry(snapIfUninitialized = true)
        if (!metrics.valid || tabCenters.isEmpty()) {
            drawChildren(canvas)
            return
        }

        // Catch any external tab switch (e.g., ViewPager swipe) even if caller didn't invoke syncSelection().
        if (activePointerId == MotionEvent.INVALID_POINTER_ID && !pageScrollTracking) {
            val checkedIdx = resolveCheckedMenuIndex()
            if (checkedIdx in tabCenters.indices && checkedIdx != lastKnownSelectedIndex) {
                lastKnownSelectedIndex = checkedIdx
                val targetX = tabCenters[checkedIdx]
                val targetW = targetRestWidthForIndex(checkedIdx)
                if (!physics.initialized || !motionEnabled()) {
                    physics.snapToRest(targetX, targetW)
                } else {
                    physics.targetCx = targetX
                    physics.targetW = targetW
                    startFrameLoop()
                }
            }
        }

        val geom = scratchGeometry
        physics.evaluateGeometryInto(metrics, geom)
        val halfW = geom.width * 0.5f
        val halfH = geom.height * 0.5f
        dropletRect.set(geom.cx - halfW, geom.cy - halfH, geom.cx + halfW, geom.cy + halfH)
        val cornerRadius = min(halfW, halfH)
        dropletPath.rewind()
        dropletPath.addRoundRect(dropletRect, cornerRadius, cornerRadius, Path.Direction.CW)

        val w = glassBar.width.toFloat()
        val h = glassBar.height.toFloat()
        layerBounds.set(0f, 0f, w, h)

        val lift = geom.lift
        val restWeight = (1f - smoothstep(0f, 0.48f, lift)).coerceIn(0f, 1f)

        // 1. Resting state: translucent liquid-glass selection pill + crisp accent tab.
        // At settled IDLE rest (!maskingMode), host already carries the 2-state
        // selected/unselected ColorStateList so we render in a single direct pass with zero
        // offscreen saveLayer FBOs and zero clipOutPath overhead during 120fps feed scrolling.
        if (lift <= 0.002f) {
            if (host.isScrollable) {
                canvas.withClip(
                    glassBar.paddingLeft.toFloat(),
                    0f,
                    (w - glassBar.paddingRight).coerceAtLeast(glassBar.paddingLeft.toFloat()),
                    h,
                ) {
                    drawRestingGlassPill(this, cornerRadius, 1f)
                }
            } else {
                drawRestingGlassPill(canvas, cornerRadius, 1f)
            }
            if (!maskingMode && restingTintList != null) {
                drawChildren(canvas)
            } else {
                drawUnselectedOutsideDroplet(canvas, drawChildren)
                canvas.withClip(dropletPath) {
                    val selSave = saveLayer(dropletRect, selectedLayerPaint)
                    try {
                        drawChildren(this)
                    } finally {
                        restoreToCount(selSave)
                    }
                }
            }
            return
        }

        // 2. Elevated Liquid Glass Droplet Loupe active (press / drag / release transition):
        val glass = smoothstep(0.05f, 0.72f, lift)
        val mag = 1f + LOUPE_MAGNIFICATION_MAX * glass
        selectedLayerBounds.set(
            dropletRect.left - 4f,
            dropletRect.top - 4f,
            dropletRect.right + 4f,
            dropletRect.bottom + 4f,
        )
        val hasSelectedBounds = selectedLayerBounds.intersect(layerBounds)

        val useAgslLoupe = Build.VERSION.SDK_INT >= 33 &&
            canvas.isHardwareAccelerated &&
            glassBar.effectiveQuality == WitLiquidGlassQuality.ULTRA &&
            WitLiquidGlassBackend.REFRACTION.available

        if (useAgslLoupe) {
            // Clip unselected tabs cleanly outside the droplet so unmagnified icons never ghost at the AA rim.
            drawUnselectedOutsideDroplet(canvas, drawChildren)
            val drawn = WitLiquidGlassBackend.REFRACTION.attempt {
                val shader = loupeShader ?: WitLiquidGlassLoupeShader().also { loupeShader = it }
                val hasSceneBounds = glassBar.getLiveBackdropBoundsInLocal(sceneBoundsLocal)
                shader.draw(
                    canvas = canvas,
                    barWidth = glassBar.width,
                    barHeight = glassBar.height,
                    density = density,
                    dropCx = geom.cx,
                    dropCy = geom.cy,
                    dropW = geom.width,
                    dropH = geom.height,
                    maxDropW = maxOf(metrics.heldW, geom.width) * 1.22f,
                    maxDropH = metrics.heldH * 1.10f,
                    lift = lift,
                    velocityStretch = geom.velocityStretch,
                    isDark = isDarkTheme,
                    sceneBoundsLocal = if (hasSceneBounds) sceneBoundsLocal else null,
                ) { contentCanvas ->
                    // Plane 1A (1.0x un-zoomed): sharp live backdrop behind the bar.
                    glassBar.drawLiveBackdropInLocal(contentCanvas)
                    // Plane 1B (1.0x unscaled outer glass bar platter):
                    // Unified dome-meniscus refraction (+normal * amp * u^a * (1 - u)^b) in
                    // WitLiquidGlassLoupeShader naturally recesses the 1.0x bar inward on top, bottom,
                    // AND left/right semicircular caps with exact 0.0px corner continuity.
                    glassBar.drawGlassPlatter(contentCanvas)
                    if (restWeight > 0.004f) {
                        drawRestingGlassPill(contentCanvas, cornerRadius, restWeight)
                    }
                    // Plane 2 (1.14x crisp vector-magnified): selected accent tab icons & labels.
                    if (hasSelectedBounds) {
                        contentCanvas.withScale(mag, mag, geom.cx, geom.cy) {
                            val selSave = saveLayer(selectedLayerBounds, selectedLayerPaint)
                            try {
                                drawChildren(this)
                            } finally {
                                restoreToCount(selSave)
                            }
                        }
                    }
                }
            }
            if (drawn == true) return
        }

        // 3. Translucent live-backdrop fallback loupe (STANDARD quality / API 24-32 / software snapshots):
        drawUnselectedOutsideDroplet(canvas, drawChildren)
        drawFallbackLoupe(canvas, geom, cornerRadius, glass, mag, restWeight, hasSelectedBounds, drawChildren)
    }

    /**
     * Draws a sleek, translucent nested liquid-glass capsule for the IDLE selected tab:
     * subtle translucent tint + soft vertical glass sheen + whisper-thin directional specular rim
     * so live blurred backdrop flows cleanly through instead of an opaque solid patch.
     */
    private fun drawRestingGlassPill(canvas: Canvas, cornerRadius: Float, weight: Float) {
        val w = weight.coerceIn(0f, 1f)
        if (w <= 0.004f) return
        val baseAlpha = Color.alpha(restingPillColor)
        if (baseAlpha > 0) {
            restingPillPaint.color = ColorUtils.setAlphaComponent(
                restingPillColor,
                (baseAlpha * w).toInt().coerceIn(0, 255),
            )
            canvas.drawRoundRect(dropletRect, cornerRadius, cornerRadius, restingPillPaint)
        }
        if (restingSheenShader == null || restingRimShader == null || restingShaderDark != isDarkTheme) {
            restingShaderDark = isDarkTheme
            restingSheenShader = LinearGradient(
                0f, 0f, 0f, 1f,
                if (isDarkTheme) {
                    intArrayOf(0x12FFFFFF, 0x05FFFFFF, 0x00FFFFFF, 0x08FFFFFF)
                } else {
                    intArrayOf(0x0B1A1A2E, 0x031A1A2E, 0x001A1A2E, 0x04FFFFFF)
                },
                floatArrayOf(0f, 0.34f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            restingRimShader = LinearGradient(
                0f, 0f, 0f, 1f,
                if (isDarkTheme) {
                    intArrayOf(0x12FFFFFF, 0x04FFFFFF, 0x03FFFFFF, 0x0CFFFFFF)
                } else {
                    intArrayOf(0x1A1A1A2E, 0x101A1A2E, 0x101A1A2E, 0x141A1A2E)
                },
                floatArrayOf(0f, 0.30f, 0.70f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val rw = dropletRect.width().coerceAtLeast(1f)
        val rh = dropletRect.height().coerceAtLeast(1f)
        restingShaderMatrix.setScale(rw, rh)
        restingShaderMatrix.postTranslate(dropletRect.left, dropletRect.top)
        restingSheenShader?.let { sheen ->
            sheen.setLocalMatrix(restingShaderMatrix)
            restingSheenPaint.shader = sheen
            restingSheenPaint.alpha = (w * 255f).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(dropletRect, cornerRadius, cornerRadius, restingSheenPaint)
        }
        restingRimShader?.let { rim ->
            rim.setLocalMatrix(restingShaderMatrix)
            val strokeW = max(1f, 0.75f * density)
            val halfStroke = strokeW * 0.5f
            restingRimPaint.shader = rim
            restingRimPaint.strokeWidth = strokeW
            restingRimPaint.alpha = (w * 255f).toInt().coerceIn(0, 255)
            val rimR = (cornerRadius - halfStroke).coerceAtLeast(0f)
            canvas.drawRoundRect(
                dropletRect.left + halfStroke,
                dropletRect.top + halfStroke,
                dropletRect.right - halfStroke,
                dropletRect.bottom - halfStroke,
                rimR,
                rimR,
                restingRimPaint,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun drawUnselectedOutsideDroplet(canvas: Canvas, drawChildren: (Canvas) -> Unit) {
        val clipSave = canvas.save()
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                canvas.clipOutPath(dropletPath)
            } else {
                canvas.clipPath(dropletPath, Region.Op.DIFFERENCE)
            }
            val unselSave = canvas.saveLayer(layerBounds, unselectedLayerPaint)
            try {
                drawChildren(canvas)
            } finally {
                canvas.restoreToCount(unselSave)
            }
        } finally {
            canvas.restoreToCount(clipSave)
        }
    }

    private fun drawFallbackLoupe(
        canvas: Canvas,
        geom: MutableDropletGeometry,
        cornerRadius: Float,
        glass: Float,
        mag: Float,
        restWeight: Float,
        hasSelectedBounds: Boolean,
        drawChildren: (Canvas) -> Unit,
    ) {
        canvas.withClip(dropletPath) {
            // Preserve transparent crystal look in STANDARD mode by compositing live backdrop + outer platter
            // inside the protruding droplet rather than an opaque gray oval.
            val drewBackdrop = glassBar.drawLiveBackdropInLocal(this)
            if (drewBackdrop) {
                val bw = glassBar.width.toFloat()
                val bh = glassBar.height.toFloat()
                val barInset = (bh * 0.5f) * (1f - INNER_BAR_RECESS_SCALE_Y) * glass
                if (barInset > 0.25f && bw - 2f * barInset > 2f && bh - 2f * barInset > 2f) {
                    innerBarRect.set(barInset, barInset, bw - barInset, bh - barInset)
                    val innerRad = min(innerBarRect.width(), innerBarRect.height()) * 0.5f
                    innerBarPath.rewind()
                    innerBarPath.addRoundRect(innerBarRect, innerRad, innerRad, Path.Direction.CW)
                    withClip(innerBarPath) {
                        glassBar.drawGlassPlatter(this)
                    }
                } else {
                    glassBar.drawGlassPlatter(this)
                }
                fallbackGlassFillPaint.color = if (isDarkTheme) {
                    ColorUtils.setAlphaComponent(Color.WHITE, (glass * 10f).toInt().coerceIn(0, 255))
                } else {
                    ColorUtils.setAlphaComponent(Color.WHITE, (glass * 38f).toInt().coerceIn(0, 255))
                }
            } else {
                fallbackGlassFillPaint.color = if (isDarkTheme) {
                    ColorUtils.setAlphaComponent(0xFF282B33.toInt(), (glass * 150f).toInt().coerceIn(0, 255))
                } else {
                    ColorUtils.setAlphaComponent(Color.WHITE, (glass * 175f).toInt().coerceIn(0, 255))
                }
            }
            drawRoundRect(dropletRect, cornerRadius, cornerRadius, fallbackGlassFillPaint)

            if (restWeight > 0.004f) {
                drawRestingGlassPill(this, cornerRadius, restWeight)
            }

            if (hasSelectedBounds) {
                withScale(mag, mag, geom.cx, geom.cy) {
                    val selSave = saveLayer(selectedLayerBounds, selectedLayerPaint)
                    try {
                        drawChildren(this)
                    } finally {
                        restoreToCount(selSave)
                    }
                }
            }
        }

        // Full-360° translucent Fresnel rim + subtle inner meniscus cushion matching ULTRA aesthetics
        if (glass > 0.01f) {
            if (fallbackRimShader == null || fallbackRimDark != isDarkTheme) {
                fallbackRimDark = isDarkTheme
                fallbackRimShader = LinearGradient(
                    0f, 0f, 0.55f, 1f,
                    intArrayOf(
                        if (isDarkTheme) 0x88FFFFFF.toInt() else 0xB8FFFFFF.toInt(),
                        if (isDarkTheme) 0x26FFFFFF else 0x36FFFFFF,
                        if (isDarkTheme) 0x26FFFFFF else 0x36FFFFFF,
                        if (isDarkTheme) 0x74FFFFFF else 0x9EFFFFFF.toInt(),
                    ),
                    floatArrayOf(0f, 0.34f, 0.66f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            val strokeW = max(1f, 0.95f * density)
            val halfStroke = strokeW * 0.5f
            val rimR = (cornerRadius - halfStroke).coerceAtLeast(0f)

            // Soft 3D inner meniscus bevel ring
            val innerW = max(1.5f, 2.2f * density)
            val innerInset = strokeW + innerW * 0.5f
            val innerR = (cornerRadius - innerInset).coerceAtLeast(0f)
            fallbackInnerBevelPaint.color = if (isDarkTheme) {
                ColorUtils.setAlphaComponent(0xFFF2F7FF.toInt(), (glass * 24f).toInt().coerceIn(0, 255))
            } else {
                ColorUtils.setAlphaComponent(Color.WHITE, (glass * 54f).toInt().coerceIn(0, 255))
            }
            fallbackInnerBevelPaint.strokeWidth = innerW
            canvas.drawRoundRect(
                dropletRect.left + innerInset,
                dropletRect.top + innerInset,
                dropletRect.right - innerInset,
                dropletRect.bottom - innerInset,
                innerR,
                innerR,
                fallbackInnerBevelPaint,
            )

            // Continuous ambient perimeter definition (subtle dark edge on light mode, icy-white on dark)
            fallbackBaseRimPaint.color = if (isDarkTheme) {
                ColorUtils.setAlphaComponent(0xFFEAF2FF.toInt(), (glass * 48f).toInt().coerceIn(0, 255))
            } else {
                ColorUtils.setAlphaComponent(0xFF1A1A2E.toInt(), (glass * 36f).toInt().coerceIn(0, 255))
            }
            fallbackBaseRimPaint.strokeWidth = strokeW
            canvas.drawRoundRect(
                dropletRect.left + halfStroke,
                dropletRect.top + halfStroke,
                dropletRect.right - halfStroke,
                dropletRect.bottom - halfStroke,
                rimR,
                rimR,
                fallbackBaseRimPaint,
            )

            // Paired directional specular rim highlight
            val rimShader = fallbackRimShader
            if (rimShader != null) {
                fallbackRimMatrix.setScale(
                    dropletRect.width().coerceAtLeast(1f),
                    dropletRect.height().coerceAtLeast(1f),
                )
                fallbackRimMatrix.postTranslate(dropletRect.left, dropletRect.top)
                rimShader.setLocalMatrix(fallbackRimMatrix)
                fallbackRimPaint.shader = rimShader
                fallbackRimPaint.strokeWidth = strokeW
                fallbackRimPaint.alpha = (glass * 255f).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(
                    dropletRect.left + halfStroke,
                    dropletRect.top + halfStroke,
                    dropletRect.right - halfStroke,
                    dropletRect.bottom - halfStroke,
                    rimR,
                    rimR,
                    fallbackRimPaint,
                )
            }
        }
    }

    private fun configureHostForLoupe() {
        host.configureForLoupe()
        applyHostTint()
    }

    private fun setMaskingMode(active: Boolean) {
        if (maskingMode == active) return
        maskingMode = active
        if (enabledFlag) {
            applyHostTint()
        }
    }

    private fun applyHostTint() {
        host.applyTint(maskingMode, restingTintList, opaqueWhiteTint)
    }

    private fun targetRestWidthForIndex(index: Int): Float {
        if (!host.usesVariableTabWidths) return 0f
        if (index !in tabWidths.indices || !metrics.valid) return metrics.restW
        val raw = tabWidths[index]
        val insetH = 2f * density
        val maxAllowed = (metrics.barW - 2f * max(glassBar.paddingLeft, glassBar.paddingRight))
            .coerceAtLeast(metrics.restH)
        return (raw - insetH).coerceIn(metrics.restH * 1.15f, maxAllowed)
    }

    private fun visibleDragMinCx(): Float {
        if (!host.isScrollable || host.maxScrollX() <= 0) return metrics.minCx
        val pad = max(glassBar.paddingLeft.toFloat(), metrics.restH * 0.65f)
        return max(metrics.minCx, pad)
    }

    private fun visibleDragMaxCx(): Float {
        if (!host.isScrollable || host.maxScrollX() <= 0) return metrics.maxCx
        val pad = max(glassBar.paddingRight.toFloat(), metrics.restH * 0.65f)
        return min(metrics.maxCx, metrics.barW - pad)
    }

    private fun maybeAutoPanScrollableHost(dtSec: Float): Boolean {
        if (!host.isScrollable || !didDrag || !physics.dragging ||
            activePointerId == MotionEvent.INVALID_POINTER_ID
        ) {
            return false
        }
        val maxScroll = host.maxScrollX()
        if (maxScroll <= 0) return false
        val edgeZone = 42f * density
        val speedPxPerSec = 360f * density
        val panDir = when {
            lastMoveX < edgeZone && host.scrollX() > 0 ->
                -((edgeZone - lastMoveX) / edgeZone).coerceIn(0f, 1f)
            lastMoveX > metrics.barW - edgeZone && host.scrollX() < maxScroll ->
                ((lastMoveX - (metrics.barW - edgeZone)) / edgeZone).coerceIn(0f, 1f)
            else -> 0f
        }
        if (abs(panDir) <= 0.01f) return false
        val dx = (panDir * speedPxPerSec * dtSec).roundToInt()
        if (dx == 0) return false
        val prevScroll = host.scrollX()
        host.scrollBy(dx)
        if (host.scrollX() == prevScroll) return false
        geometryDirty = true
        refreshTabGeometry(snapIfUninitialized = false)
        val hover = nearestTabIndex(physics.targetCx)
        physics.targetW = targetRestWidthForIndex(hover)
        if (hover != currentHoverIndex) {
            currentHoverIndex = hover
            if (hover != initialSelectedIndex) {
                crossedOtherTab = true
            }
            performTabBoundaryHaptic()
        }
        return true
    }

    private fun refreshTabGeometry(snapIfUninitialized: Boolean) {
        val barW = glassBar.width.toFloat()
        val barH = glassBar.height.toFloat()
        if (barW <= 0f || barH <= 0f) return

        val count = host.tabCount
        if (count <= 0) {
            if (tabCenters.isNotEmpty() || metrics.valid) {
                tabCenters = FloatArray(0)
                tabWidths = FloatArray(0)
                tabMenuIds = IntArray(0)
                metrics = BarMetrics(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            geometryDirty = false
            return
        }

        val curDensity = density
        val navDir = host.hostView.layoutDirection
        val curScrollX = host.scrollX()

        // Zero-allocation fast path for 120fps draw/scroll frames when geometry is unchanged.
        if (!geometryDirty &&
            barW == cachedBarW &&
            barH == cachedBarH &&
            curDensity == cachedDensity &&
            navDir == cachedLayoutDir &&
            curScrollX == cachedScrollX &&
            count == tabCenters.size &&
            metrics.valid &&
            host.isCachedGeometryExact(glassBar, tabCenters, tabWidths, tabMenuIds, count)
        ) {
            if (snapIfUninitialized && !physics.initialized) {
                val checkedIdx = resolveCheckedMenuIndex().coerceIn(0, tabCenters.lastIndex)
                lastKnownSelectedIndex = checkedIdx
                physics.snapToRest(tabCenters[checkedIdx], targetRestWidthForIndex(checkedIdx))
                applyOuterBarTransform()
            }
            return
        }

        val centers = if (tabCenters.size == count) tabCenters else FloatArray(count)
        val widths = if (tabWidths.size == count) tabWidths else FloatArray(count)
        val ids = if (tabMenuIds.size == count) tabMenuIds else IntArray(count)
        if (!host.readTabGeometry(glassBar, centers, widths, ids)) return

        tabCenters = centers
        tabWidths = widths
        tabMenuIds = ids
        val baseMetrics = computeMetrics(barW, barH, centers, curDensity)
        metrics = if (host.usesVariableTabWidths) {
            var maxTabW = baseMetrics.restW
            for (w in widths) {
                if (w > maxTabW) maxTabW = w
            }
            val protrudeV = (baseMetrics.heldH - baseMetrics.barH).coerceAtLeast(0f) * 0.5f
            val maxHeldW = (maxTabW + protrudeV * 2.24f).coerceAtMost(barW * 1.12f)
            baseMetrics.copy(
                restW = maxTabW.coerceIn(baseMetrics.restH * 1.15f, barW),
                heldW = max(baseMetrics.heldW, maxHeldW),
            )
        } else {
            baseMetrics
        }
        cachedBarW = barW
        cachedBarH = barH
        cachedDensity = curDensity
        cachedLayoutDir = navDir
        cachedScrollX = curScrollX
        geometryDirty = false

        val checkedIdx = resolveCheckedMenuIndex().coerceIn(0, centers.lastIndex)
        val checkedW = targetRestWidthForIndex(checkedIdx)
        if (snapIfUninitialized && !physics.initialized) {
            lastKnownSelectedIndex = checkedIdx
            physics.snapToRest(centers[checkedIdx], checkedW)
            applyOuterBarTransform()
        } else if (activePointerId == MotionEvent.INVALID_POINTER_ID && !physics.pressed &&
            !frameLoopRunning && !pageScrollTracking
        ) {
            lastKnownSelectedIndex = checkedIdx
            physics.snapToRest(centers[checkedIdx], checkedW)
        }
    }

    private fun resolveCheckedMenuIndex(): Int = host.selectedIndex()

    private fun nearestTabIndex(x: Float): Int =
        resolveTabIndexAt(x, tabCenters, tabWidths, host.usesVariableTabWidths)

    private fun applyOuterBarTransform() {
        val w = glassBar.width.toFloat()
        val h = glassBar.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val yRatio = (BAR_PRESS_SCALE_Y - 1f) / (BAR_PRESS_SCALE - 1f)
        val sx = (physics.barScale.value + physics.barPullStretchX.value).coerceIn(0.93f, 1.12f)
        val sy = (1f + (physics.barScale.value - 1f) * yRatio).coerceIn(0.93f, 1.14f)
        val tx = physics.barShiftX.value
        val ty = physics.barShiftY.value
        if (glassBar.pivotX != w * 0.5f) glassBar.pivotX = w * 0.5f
        if (glassBar.pivotY != h * 0.5f) glassBar.pivotY = h * 0.5f
        if (glassBar.scaleX != sx) glassBar.scaleX = sx
        if (glassBar.scaleY != sy) glassBar.scaleY = sy
        if (tx != appliedBarShiftX) {
            glassBar.translationX = (glassBar.translationX - appliedBarShiftX) + tx
            appliedBarShiftX = tx
        }
        if (ty != appliedBarShiftY) {
            glassBar.translationY = (glassBar.translationY - appliedBarShiftY) + ty
            appliedBarShiftY = ty
        }
    }

    private fun startFrameLoop() {
        if (!frameLoopRunning) {
            lastFrameUptimeMs = SystemClock.uptimeMillis()
            scheduleNextFrame()
        }
    }

    private fun scheduleNextFrame() {
        if (frameLoopRunning) return
        frameLoopRunning = true
        glassBar.postOnAnimation(frameRunnable)
    }

    private fun pauseForWindowDetach() {
        glassBar.removeCallbacks(frameRunnable)
        frameLoopRunning = false
        lastFrameUptimeMs = 0L
        activePointerId = MotionEvent.INVALID_POINTER_ID
        pendingReleaseAtMs = 0L
        pageScrollTracking = false
        setMaskingMode(false)
        geometryDirty = true
        physics.initialized = false
        resetVisualState()
        if (Build.VERSION.SDK_INT >= 33) {
            loupeShader?.clear()
        }
    }

    private fun cancelAndReset() {
        glassBar.removeCallbacks(frameRunnable)
        frameLoopRunning = false
        lastFrameUptimeMs = 0L
        activePointerId = MotionEvent.INVALID_POINTER_ID
        pendingReleaseAtMs = 0L
        pageScrollTracking = false
        maskingMode = false
        geometryDirty = true
        physics.initialized = false
        host.uninstallObservers()
        host.restoreOnDetach()
        resetVisualState()
        if (Build.VERSION.SDK_INT >= 33) {
            loupeShader?.clear()
        }
    }

    private fun resetVisualState() {
        glassBar.scaleX = 1f
        glassBar.scaleY = 1f
        if (appliedBarShiftX != 0f) {
            glassBar.translationX -= appliedBarShiftX
            appliedBarShiftX = 0f
        }
        if (appliedBarShiftY != 0f) {
            glassBar.translationY -= appliedBarShiftY
            appliedBarShiftY = 0f
        }
        glassBar.invalidate()
    }

    private fun performTabBoundaryHaptic() {
        val flag = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        glassBar.performHapticFeedback(flag)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
