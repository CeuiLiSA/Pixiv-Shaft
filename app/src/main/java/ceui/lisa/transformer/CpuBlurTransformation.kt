package ceui.lisa.transformer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import jp.wasabeef.glide.transformations.internal.FastBlur
import java.security.MessageDigest

/**
 * 降采样 + 纯 CPU StackBlur，参数与画面和 wasabeef 的 BlurTransformation 一致，但不走 RenderScript。
 *
 * wasabeef 4.3.0 的 RSBlur 每次模糊完都调进程级的 RenderScript.releaseAllContexts()，
 * Glide 多个 source 线程同时模糊时会拆掉别的线程还在用的上下文，驱动线程空指针 SIGSEGV（#1263）。
 * 降采样后的小图跑 StackBlur 只要几毫秒，没有理由再碰 RS。
 */
class CpuBlurTransformation(
    private val radius: Int,
    private val sampling: Int,
) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val scaled = pool.get(toTransform.width / sampling, toTransform.height / sampling, Bitmap.Config.ARGB_8888)
        scaled.density = toTransform.density
        Canvas(scaled).apply {
            scale(1f / sampling, 1f / sampling)
            drawBitmap(toTransform, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return FastBlur.blur(scaled, radius, true)
    }

    override fun equals(other: Any?): Boolean =
        other is CpuBlurTransformation && other.radius == radius && other.sampling == sampling

    override fun hashCode(): Int = ID.hashCode() + radius * 1000 + sampling * 10

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID$radius$sampling".toByteArray(CHARSET))
    }

    private companion object {
        const val ID = "ceui.lisa.transformer.CpuBlurTransformation.1"
    }
}
