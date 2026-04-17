package ceui.lisa.utils

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * Generates a full V3 dark-theme color palette from the current theme's colorPrimary.
 * All accent-derived colors are computed at runtime so every theme index "just works".
 *
 * Usage:
 *   val p = V3Palette.from(context)
 *   followBtn.background = p.pillPrimary(...)
 *   tagCount.setTextColor(p.textAccent)
 */
class V3Palette(@ColorInt val primary: Int, val isDark: Boolean = true) {

    // ── derived alphas ──────────────────────────────────────────────

    /** 8 % — tag locked background tint */
    @ColorInt val alpha08: Int = withAlpha(primary, 0.08f)

    /** 10 % — very subtle tint (tag count badge, shimmer) */
    @ColorInt val alpha10: Int = withAlpha(primary, 0.10f)

    /** 15 % — tag locked border, slight surfaces */
    @ColorInt val alpha15: Int = withAlpha(primary, 0.15f)

    /** 20 % — secondary button / chip fill */
    @ColorInt val alpha20: Int = withAlpha(primary, 0.20f)

    /** 30 % — secondary button stroke */
    @ColorInt val alpha30: Int = withAlpha(primary, 0.30f)

    /** 50 % — accent line, medium emphasis */
    @ColorInt val alpha50: Int = withAlpha(primary, 0.50f)

    /** 60 % — artist banner overlay */
    @ColorInt val alpha60: Int = withAlpha(primary, 0.60f)

    // ── text colors ─────────────────────────────────────────────────

    /** Primary accent text — adjusted for background readability */
    @ColorInt val textAccent: Int = if (isDark) ensureLightEnough(primary, 0.60f)
        else ensureDarkEnough(primary, 0.40f)

    /** Variant for secondary button label */
    @ColorInt val textSecondary: Int = if (isDark)
        withAlpha(ensureLightEnough(primary, 0.72f), 0.90f)
    else withAlpha(ensureDarkEnough(primary, 0.35f), 0.90f)

    /** Tag locked text */
    @ColorInt val textTag: Int = if (isDark) ensureLightEnough(primary, 0.70f)
        else ensureDarkEnough(primary, 0.38f)

    /** Series label text */
    @ColorInt val textSeries: Int = if (isDark)
        withAlpha(ensureLightEnough(primary, 0.68f), 0.70f)
    else withAlpha(ensureDarkEnough(primary, 0.35f), 0.70f)

    // ── scroll progress gradient ────────────────────────────────────

    /** Scroll progress bar: primary → shifted hue → gold */
    @ColorInt val scrollProgressStart: Int = primary
    @ColorInt val scrollProgressMid: Int = hueShift(primary, 40f)
    @ColorInt val scrollProgressEnd: Int = 0xFFFFC233.toInt()

    // ── drawable factories ──────────────────────────────────────────

    /** Solid pill — follow button */
    fun pillPrimary(radiusPx: Float = 999f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(primary)
        }

    /** Semi-transparent pill with stroke — unfollow / secondary button */
    fun pillSecondary(radiusPx: Float = 999f, strokePx: Int = 2): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(alpha20)
            setStroke(strokePx, alpha30)
        }

    /** Tag count badge background */
    fun tagCountBg(radiusPx: Float = 999f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(alpha10)
        }

    /** Tag locked background (author tags) */
    fun tagLockedBg(radiusPx: Float = 999f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(alpha08)
            setStroke(1, alpha15)
        }

    /** Accent line (horizontal gradient: transparent → accent → accent → transparent) */
    fun accentLine(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00000000, alpha50, hueShift(alpha50, 30f), 0x00000000)
        )

    /** Banner placeholder — vivid ambient gradient matching theme color */
    fun bannerPlaceholder(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(
                if (isDark) darken(primary, 0.18f) else lighten(primary, 0.82f),
                if (isDark) darken(hueShift(primary, 30f), 0.14f) else lighten(hueShift(primary, 30f), 0.86f),
                if (isDark) darken(hueShift(primary, -20f), 0.20f) else lighten(hueShift(primary, -20f), 0.80f)
            )
        )
    }

    /** Artist banner overlay gradient */
    fun artistBannerBg(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(alpha60, withAlpha(hueShift(primary, 30f), 0.50f))
        )

    /** Series strip gradient background */
    fun seriesStripBg(radiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(
                withAlpha(primary, 0.35f),
                withAlpha(hueShift(primary, 25f), 0.30f)
            )
        ).apply {
            cornerRadius = radiusPx
            setStroke(1, alpha15)
        }

    /** Series icon square background */
    fun seriesIconBg(radiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(primary, hueShift(primary, 40f))
        ).apply {
            cornerRadius = radiusPx
        }

    /** Detail panel / glass card background */
    fun glassCardBg(radiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                withAlpha(desaturate(primary, 0.4f), 0.45f),
                withAlpha(desaturate(primary, 0.25f), 0.35f)
            )
        ).apply {
            cornerRadius = radiusPx
            setStroke(1, 0x0FFFFFFF)
        }

    // ── convenience ─────────────────────────────────────────────────

    /** Apply accent-colored follow button drawable */
    fun applyFollowBtn(btn: View) {
        btn.background = pillPrimary(999f * btn.resources.displayMetrics.density)
    }

    /** Apply accent-colored unfollow button drawable + text */
    fun applyUnfollowBtn(btn: TextView) {
        val d = btn.resources.displayMetrics.density
        btn.background = pillSecondary(999f * d, (1 * d).toInt())
        btn.setTextColor(textSecondary)
    }

    // ── companion ───────────────────────────────────────────────────
    companion object {

        /** Resolve the palette from the current theme's colorPrimary */
        fun from(context: Context): V3Palette {
            val primary = Common.resolveThemeAttribute(
                context, androidx.appcompat.R.attr.colorPrimary
            )
            val nightMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            return V3Palette(primary, isDark)
        }

        @ColorInt
        fun withAlpha(@ColorInt color: Int, alpha: Float): Int =
            ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

        @ColorInt
        private fun ensureLightEnough(@ColorInt color: Int, minL: Float = 0.60f): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            if (hsl[2] < minL) hsl[2] = minL
            return ColorUtils.HSLToColor(hsl)
        }

        /** For light mode — darken a color so it's readable on white backgrounds */
        @ColorInt
        private fun ensureDarkEnough(@ColorInt color: Int, maxL: Float = 0.40f): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            if (hsl[2] > maxL) hsl[2] = maxL
            return ColorUtils.HSLToColor(hsl)
        }

        /** Shift hue by [degrees] while keeping saturation and lightness */
        @ColorInt
        private fun hueShift(@ColorInt color: Int, degrees: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[0] = (hsl[0] + degrees) % 360f
            val shifted = ColorUtils.HSLToColor(hsl)
            // preserve original alpha
            return ColorUtils.setAlphaComponent(shifted, (color ushr 24) and 0xFF)
        }

        /** Set lightness to a specific value */
        @ColorInt
        private fun darken(@ColorInt color: Int, lightness: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[2] = lightness
            return ColorUtils.HSLToColor(hsl)
        }

        @ColorInt
        private fun lighten(@ColorInt color: Int, lightness: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[2] = lightness
            return ColorUtils.HSLToColor(hsl)
        }

        /** Reduce saturation towards gray */
        @ColorInt
        private fun desaturate(@ColorInt color: Int, factor: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[1] *= factor
            return ColorUtils.HSLToColor(hsl)
        }
    }
}
