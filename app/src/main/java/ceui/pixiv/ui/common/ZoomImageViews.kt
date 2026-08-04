package ceui.pixiv.ui.common

import android.graphics.Paint
import com.github.panpf.zoomimage.ZoomImageView
import timber.log.Timber

/**
 * 给 zoomimage 的子采样瓦片绘制打开双线性过滤（issue #735）。
 *
 * zoomimage view 版（截至 1.6.0）的 TileDrawHelper 用裸 Paint() 画瓦片，
 * isFilterBitmap 默认关闭，瓦片缩放绘制走最近邻采样。低分辨率屏幕（如 1920×1200
 * 平板）看高清原图时，Sketch 底图按 LESS_PIXELS 面积规则会采样到比屏幕还小
 * （5000×3000 → 1250×750），fit 缩放 > 1 使瓦片在不放大的初始状态就常驻整屏，
 * 未过滤的缩小绘制（约 0.77x）呈现明显锯齿。高分屏手机 fit 缩放 < 1 不触发瓦片，
 * 所以只有低分屏中招。
 *
 * tilePaint 是上游 private 字段，只能反射打开；拿不到（库升级改了内部结构）就
 * 静默退回未过滤画质，不影响任何功能。
 */
fun ZoomImageView.enableTileBitmapFilter() {
    runCatching {
        val helperField = ZoomImageView::class.java.getDeclaredField("tileDrawHelper")
            .apply { isAccessible = true }
        val helper = helperField.get(this) ?: return
        val paintField = helper.javaClass.getDeclaredField("tilePaint")
            .apply { isAccessible = true }
        (paintField.get(helper) as Paint).isFilterBitmap = true
    }.onFailure {
        Timber.w(it, "enableTileBitmapFilter failed, tiles stay unfiltered")
    }
}
