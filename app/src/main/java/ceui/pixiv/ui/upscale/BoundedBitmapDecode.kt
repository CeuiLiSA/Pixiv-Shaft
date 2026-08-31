package ceui.pixiv.ui.upscale

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import java.io.File

/**
 * 喂给 ncnn 进程(超分/抠图)之前的输入图解码上限,按总像素数计。
 *
 * 32MP ≈ 128MB ARGB_8888:普通插画(≤ 5000x6000)完全不受影响,只有几十 MP 的超长条漫
 * 才会被折半 —— 这种尺寸整张解进 Java 堆会直接 OOM(largeHeap 也兜不住),
 * 而且它本来也早就超出「需要 2x 放大」的范围了。按像素数而不是短边算,是因为
 * 条漫短边不大、长边极长,短边上限根本约束不住内存。
 */
internal const val NCNN_INPUT_MAX_PIXELS = 32L * 1024 * 1024

/**
 * 解码 [file] 为 ARGB_8888,总像素超过 [maxPixels] 时用 inSampleSize 逐级折半直到落进预算。
 * 解不开返回 null。同步阻塞,调用方负责在 IO 线程。
 */
internal fun decodeBoundedArgb(file: File, tag: String, maxPixels: Long = NCNN_INPUT_MAX_PIXELS): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val w = bounds.outWidth.toLong()
    val h = bounds.outHeight.toLong()
    var sample = 1
    while (w > 0 && h > 0 && (w / sample) * (h / sample) > maxPixels) sample *= 2
    if (sample > 1) {
        Timber.w("$tag: input ${w}x${h} exceeds ${maxPixels}px budget, inSampleSize=$sample")
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}
