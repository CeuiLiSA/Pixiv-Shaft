package ceui.pixiv.witstudio.widget.internal

import android.view.View
import androidx.annotation.RequiresApi
import ceui.pixiv.witstudio.widget.WitLiquidGlassQuality
import ceui.pixiv.witstudio.widget.WitLiquidGlassView
import ceui.pixiv.witstudio.widget.WitScrollEdgeBlurView

/**
 * Internal dispatch helpers bridging [ceui.pixiv.witstudio.widget.WitLiquidGlassLayout] /
 * [ceui.pixiv.witstudio.widget.WitLiquidGlassCoordinatorLayout] and backdrop-consuming siblings
 * ([WitLiquidGlassView] and [WitScrollEdgeBlurView]) without exposing internal types on the
 * public library API surface.
 */
internal object WitBackdropConsumers {

    internal fun isConsumer(view: View): Boolean =
        view is WitLiquidGlassView || view is WitScrollEdgeBlurView

    internal fun effectiveQuality(view: View): WitLiquidGlassQuality? = when (view) {
        is WitLiquidGlassView -> view.effectiveQuality
        is WitScrollEdgeBlurView -> view.effectiveQuality
        else -> null
    }

    internal fun planPatch(view: View, canvasEdge: Int): WitLiquidGlassBudget.PatchPlan = when (view) {
        is WitLiquidGlassView -> view.planPatch(canvasEdge)
        is WitScrollEdgeBlurView -> view.planPatch(canvasEdge)
        else -> WitLiquidGlassBudget.PatchPlan(0L, 1f)
    }

    @RequiresApi(31)
    internal fun updateBackdrop(
        view: View,
        backdrop: WitLiquidGlassBackdrop?,
        parentScrollX: Int,
        parentScrollY: Int,
        renderScale: Float = 1f,
    ) {
        when (view) {
            is WitLiquidGlassView -> view.updateBackdrop(backdrop, parentScrollX, parentScrollY, renderScale)
            is WitScrollEdgeBlurView -> view.updateBackdrop(backdrop, parentScrollX, parentScrollY, renderScale)
        }
    }

    internal fun onHostQualityChanged(view: View) {
        when (view) {
            is WitLiquidGlassView -> view.onHostQualityChanged()
            is WitScrollEdgeBlurView -> view.onHostQualityChanged()
        }
    }
}
