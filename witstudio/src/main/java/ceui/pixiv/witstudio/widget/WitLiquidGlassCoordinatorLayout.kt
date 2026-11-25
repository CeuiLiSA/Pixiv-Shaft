package ceui.pixiv.witstudio.widget

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.withClip
import androidx.core.view.isVisible
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.widget.internal.WitBackdropConsumers
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackend
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackdrop
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBudget

/**
 * A [CoordinatorLayout] that captures its first direct child as a live backdrop for sibling
 * [WitLiquidGlassView]s. Preserves all native CoordinatorLayout features and behaviors (such as
 * [com.google.android.material.behavior.HideViewOnScrollBehavior]) while providing hardware-accelerated
 * real-time liquid glass blur and refraction.
 */
public class WitLiquidGlassCoordinatorLayout @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CoordinatorLayout(context, attrs, defStyleAttr), WitLiquidGlassHost {

    private var backdrop: WitLiquidGlassBackdrop? = null
    private var drawingBackdrop: WitLiquidGlassBackdrop? = null
    private var recordedSource: View? = null
    private var backdropDirty = true
    private var remainingPixels = 0L
    private var canvasEdge = 0
    private val deviceAllowsBackdrop: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        val manager = context.getSystemService(ActivityManager::class.java)
        manager != null && !manager.isLowRamDevice && manager.memoryClass > 128
    }

    override var quality: WitLiquidGlassQuality = WitLiquidGlassQuality.defaultQuality
        set(value) {
            if (field == value) return
            field = value
            if (!value.isLiveEnabled) clearBackdrop()
            for (i in 0 until childCount) {
                WitBackdropConsumers.onHostQualityChanged(getChildAt(i))
            }
            invalidate()
        }

    override var isLiveBackdropEnabled: Boolean
        get() = quality.isLiveEnabled
        set(value) {
            quality = if (value) {
                if (quality == WitLiquidGlassQuality.NONE) WitLiquidGlassQuality.STANDARD else quality
            } else {
                WitLiquidGlassQuality.NONE
            }
        }

    init {
        clipChildren = false
        context.withStyledAttributes(attrs, R.styleable.WitLiquidGlassLayout, defStyleAttr, 0) {
            val qIdx = getInt(R.styleable.WitLiquidGlassLayout_wit_glassQuality, -1)
            if (qIdx >= 0) {
                quality = WitLiquidGlassQuality.fromOrdinal(qIdx)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val softwareSnapshot =
            !canvas.isHardwareAccelerated && isHardwareAccelerated && layerType != LAYER_TYPE_SOFTWARE
        if (!softwareSnapshot) {
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated &&
                hasLiveGlass() && WitLiquidGlassBackend.BACKDROP.available && deviceAllowsBackdrop
            ) {
                canvasEdge = minOf(canvas.maximumBitmapWidth, canvas.maximumBitmapHeight)
                remainingPixels = WitLiquidGlassBudget.HOST_PIXELS
                drawingBackdrop = WitLiquidGlassBackend.BACKDROP.attempt { recordBackdrop() }
            }
            if (drawingBackdrop == null) clearBackdrop()
        }
        try {
            canvas.withClip(scrollX, scrollY, scrollX + width, scrollY + height) {
                super.dispatchDraw(this)
            }
        } finally {
            if (!softwareSnapshot) {
                drawingBackdrop = null
                if (backdrop != null && !WitLiquidGlassBackend.BACKDROP.available) {
                    clearBackdrop()
                    invalidate()
                }
            }
        }
    }

    @RequiresApi(31)
    private fun recordBackdrop(): WitLiquidGlassBackdrop? {
        val source = getChildAt(0)
        val hasGlass = (1 until childCount).any {
            val child = getChildAt(it)
            WitBackdropConsumers.isConsumer(child) && patchPlan(child).pixels > 0
        }
        if (width == 0 || height == 0 || source == null ||
            WitBackdropConsumers.isConsumer(source) || !source.isVisible || !hasGlass
        ) {
            return null
        }

        if (backdrop?.hasDisplayList == false) clearBackdrop()
        val scene = backdrop ?: WitLiquidGlassBackdrop().also { backdrop = it }
        if (!backdropDirty && recordedSource === source && scene.hasDisplayList) return scene
        val recording = scene.begin(width, height)
        try {
            if (clipToPadding) {
                recording.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
            }
            recording.translate(-scrollX.toFloat(), -scrollY.toFloat())
            super.drawChild(recording, source, drawingTime)
        } finally {
            scene.end()
        }
        recordedSource = source
        backdropDirty = false
        return scene
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && WitBackdropConsumers.isConsumer(child)) {
            if (canvas.isHardwareAccelerated) {
                val scene = drawingBackdrop
                val plan = if (scene != null) patchPlan(child) else WitLiquidGlassBudget.PatchPlan(0L, 1f)
                if (plan.pixels > 0 && plan.pixels <= remainingPixels) {
                    remainingPixels -= plan.pixels
                    WitBackdropConsumers.updateBackdrop(child, scene, scrollX, scrollY, plan.scale)
                } else {
                    WitBackdropConsumers.updateBackdrop(child, null, scrollX, scrollY, 1f)
                }
            } else if (!isHardwareAccelerated || layerType == LAYER_TYPE_SOFTWARE) {
                WitBackdropConsumers.updateBackdrop(child, null, scrollX, scrollY, 1f)
            }
        }
        return super.drawChild(canvas, child, drawingTime)
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (Build.VERSION.SDK_INT < 31 || !isHardwareAccelerated || !deviceAllowsBackdrop) return
        if (!hasLiveGlass() || !WitLiquidGlassBackend.BACKDROP.available) {
            if (backdrop != null) {
                clearBackdrop()
                invalidate()
            }
            return
        }
        if (child === getChildAt(0) && backdrop != null) {
            backdropDirty = true
            invalidate()
        } else if (child === target && WitBackdropConsumers.isConsumer(child) && (backdrop != null || patchPlan(child).pixels > 0)) {
            invalidate()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        backdropDirty = true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        backdropDirty = true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            backdropDirty = true
            invalidate()
        } else {
            clearBackdrop()
        }
    }

    override fun onDetachedFromWindow() {
        clearBackdrop()
        super.onDetachedFromWindow()
    }

    private fun clearBackdrop() {
        if (Build.VERSION.SDK_INT >= 31) backdrop?.discard()
        backdrop = null
        recordedSource = null
        backdropDirty = true
    }

    private fun hasLiveGlass(): Boolean {
        if (quality.isLiveEnabled) return true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (WitBackdropConsumers.effectiveQuality(child)?.isLiveEnabled == true) return true
        }
        return false
    }

    private fun patchPlan(child: View): WitLiquidGlassBudget.PatchPlan {
        val q = WitBackdropConsumers.effectiveQuality(child) ?: return WitLiquidGlassBudget.PatchPlan(0L, 1f)
        if (!q.isLiveEnabled || !child.isVisible || child.alpha <= 0f || child.layerType == LAYER_TYPE_SOFTWARE) {
            return WitLiquidGlassBudget.PatchPlan(0L, 1f)
        }
        return WitBackdropConsumers.planPatch(child, canvasEdge)
    }
}
