package ceui.pixiv.ui.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

/**
 * comic-text-detector (dmMaze) Android port.
 *
 * 替代 PaddleOCR 通用文本行检测,直接给气泡/文本块级别的 bbox。
 * - 模型:YOLOv5-base + DBNet,2 类(text=0, balloon=1)
 * - 输入:[1, 3, 1024, 1024] float32, RGB / 255,letterbox 居中
 * - 输出:`blk` (1, N, K) raw YOLO anchors,K = 5 + num_classes(text + balloon = 7)
 *   还有 4D 输出(text segmentation `mask` + 方向 `lines_map`)— 现在只用 mask:
 *   单通道 sigmoid'd in [0,1] @ 1024x1024,做 inpaint 时用作精确文字像素 mask。
 *
 * 上游精度比 paddle 通用 detection 高一档,且天然按气泡分组 — 不再需要 MangaTextlineMerge。
 */
data class DetectionBox(
    /** 原图坐标系下的中心 + 尺寸(已反 letterbox 还原) */
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val classId: Int, // 0=text(free), 1=balloon(bubble)
)

/**
 * Detect 一次的结果。
 *
 * @property boxes 文本/气泡 box 列表,原图坐标系
 * @property textMask 文本像素 mask,**坐标系跟传入 bitmap 一致**(同 width/height);
 *  null = 模型没暴露可识别的 mask 输出 / 解码失败,调用方应回退到 fallback 路径
 */
data class DetectionResult(
    val boxes: List<DetectionBox>,
    val textMask: TextMask?,
)

/**
 * 1-bit-per-pixel 文本 mask,packed 成 byte 数组:row-major,每字节 0 或 1。
 * 用 ByteArray 不用 BooleanArray 因为 JVM `BooleanArray` 也是 1 byte/element 没省,
 * ByteArray 显式表意 + 跟 [Bitmap.getPixels] 等 API 互操作更顺。
 */
data class TextMask(
    val width: Int,
    val height: Int,
    /** length = width * height,每个元素 0 或 1。 */
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is TextMask && other.width == width && other.height == height && other.data.contentEquals(data)
    override fun hashCode(): Int = (width * 31 + height) * 31 + data.contentHashCode()
}

object ComicTextDetector {

    /** YOLO 输入尺寸,固定 1024。 */
    private const val INPUT_SIZE = 1024

    /** 候选 box 最低 conf。跟 dmMaze 上游 conf_thresh 一致。 */
    private const val DEFAULT_CONF_THRESHOLD = 0.4f

    /**
     * NMS IoU 阈值,跟 dmMaze 上游 nms_thresh=0.35 一致(比 YOLOv5 默认 0.45 紧)。
     *
     * 上游用 **class-aware** NMS(text/balloon 互不抑制),我们改 **class-agnostic** —
     * 理由:OCR 下游对同一气泡只想拿一个 crop;text(文字紧框)和 balloon(气泡含背景)
     * 经常都在同一气泡触发,class-aware 会两个都留,manga-ocr 跑两遍出相同文本。
     * IoU 0.35 + class-agnostic 能稳定把这对 dup 框合并到高分那个。
     */
    private const val DEFAULT_IOU_THRESHOLD = 0.35f

    /**
     * letterbox 填充色 — 跟上游 dmMaze inference.py 一致用 **black (0,0,0)**。
     * 注意:YOLOv5 训练默认是 gray 114,但 CTD 推理代码 letterbox 默认 color=(0,0,0),
     * 模型实际在黑色 padding 分布上工作,用 gray 会让边界 anchor 偏移。
     */
    private const val LETTERBOX_FILL_COLOR = 0xFF000000.toInt()

    /** mask 二值化阈值。CTD seg head 是 sigmoid'd in [0,1],dmMaze 默认 0.3 走文本框聚类,
     *  我们要的是「这个像素是不是字」更严格一些,0.5 比较安全。 */
    private const val MASK_THRESHOLD = 0.5f

    private var session: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null
    private var inputName: String = "images"
    /** 0/1/2 哪个 output 是 blk;首次跑后按形状识别 */
    private var blkOutputIndex: Int = 0
    /** 哪个 output 是 text seg mask(4D, 1-channel)。-1 = 不可用,detect 时退化为只返回 boxes。 */
    private var maskOutputIndex: Int = -1

    val isLoaded: Boolean get() = session != null

    @Synchronized
    fun loadModel(context: Context, model: ComicTextDetectorModel) {
        if (isLoaded) return
        val modelDir = ComicTextDetectorModelManager.modelDir(context, model)
        val modelFile = File(modelDir, model.modelFiles.first())
        val env = OrtEnvironment.getEnvironment()
        ortEnv = env
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }
        Timber.d("CTD: loading model from ${modelFile.absolutePath}")
        session = env.createSession(modelFile.absolutePath, opts)
        // 抓真实 input 名 — 不同导出版本可能是 "images" / "input" / "input.1"
        inputName = session!!.inputNames.firstOrNull() ?: "images"
        val outputInfos = session!!.outputInfo.entries.toList()
        // 把每个 output 的 (name, shape) 摘出来一遍,后面挑 blk / mask 都用
        val shaped = outputInfos.mapIndexedNotNull { idx, e ->
            val s = (e.value.info as? ai.onnxruntime.TensorInfo)?.shape ?: return@mapIndexedNotNull null
            Triple(idx, e.key, s)
        }
        Timber.d("CTD: input=$inputName, outputs=${shaped.map { "${it.second}${it.third.toList()}" }}")

        // 1) blk: 3D 且 K in [5..16](YOLOv5 raw anchors)— 找不到 fail-fast
        blkOutputIndex = shaped.firstOrNull { it.third.size == 3 && it.third[2] in 5..16 }?.first
            ?: run {
                session?.close(); session = null
                throw IllegalStateException("CTD: no 3-D blk output found among ${shaped.map { it.second to it.third.toList() }}")
            }

        // 2) mask: 4D 单通道(text seg head sigmoid'd)— 找不到不阻断 load,只是后面不出 mask
        //    导出版本里通常有两路 4D:`mask` (C=1) 跟 `lines_map` (C=2/3)。挑 C==1 那个。
        //    若有多个 C==1,挑空间分辨率最大的(细节最足)。
        val maskCandidate = shaped
            .filter { it.third.size == 4 && it.third[1] == 1L }
            .maxByOrNull { it.third[2] * it.third[3] }
        if (maskCandidate != null) {
            maskOutputIndex = maskCandidate.first
            Timber.d(
                "CTD: mask output index=$maskOutputIndex name=${maskCandidate.second} shape=${maskCandidate.third[2]}x${maskCandidate.third[3]}"
            )
        } else {
            maskOutputIndex = -1
            Timber.w("CTD: no single-channel 4-D mask output identified; mask disabled, fallback to threshold")
        }
    }

    @Synchronized
    fun unloadModel() {
        session?.close()
        session = null
        Timber.d("CTD: model unloaded")
    }

    /**
     * 检测一张漫画页的所有文本/气泡 region + 文本像素 mask。
     *
     * @param bitmap 原图(任意尺寸),内部会 letterbox 到 1024x1024
     * @return [DetectionResult],boxes 在原图坐标系;textMask 跟原图同尺寸(没拿到则为 null)
     */
    suspend fun detect(
        bitmap: Bitmap,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    ): DetectionResult {
        coroutineContext.ensureActive()
        val sess = session ?: throw IllegalStateException("CTD model not loaded")
        val env = ortEnv ?: throw IllegalStateException("CTD model not loaded")

        val (lb, scale, padX, padY) = letterbox(bitmap, INPUT_SIZE)
        coroutineContext.ensureActive()
        val inputBuf = try {
            bitmapToTensor(lb)
        } finally {
            if (lb !== bitmap) lb.recycle()
        }

        val inputTensor = OnnxTensor.createTensor(
            env, inputBuf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val candidates = mutableListOf<DetectionBox>()
        var textMask: TextMask? = null
        try {
            coroutineContext.ensureActive()
            val outputs = sess.run(mapOf(inputName to inputTensor))
            try {
                val blkTensor = outputs[blkOutputIndex] as OnnxTensor
                val shape = blkTensor.info.shape
                if (shape.size != 3) {
                    Timber.e("CTD: unexpected blk shape ${shape.toList()}, abort")
                    return DetectionResult(emptyList(), null)
                }
                val n = shape[1].toInt()
                val k = shape[2].toInt()
                val buf = blkTensor.floatBuffer
                // YOLOv5: [cx, cy, w, h, obj, cls0, cls1, ...]
                for (i in 0 until n) {
                    // 候选框循环里逐条检查取消,大图 N 可到几千,不能让它把取消憋到最后
                    coroutineContext.ensureActive()
                    val off = i * k
                    val obj = buf.get(off + 4)
                    if (obj < confThreshold) continue
                    var maxCls = 1f
                    var clsId = 0
                    if (k > 5) {
                        maxCls = Float.NEGATIVE_INFINITY
                        for (c in 5 until k) {
                            val v = buf.get(off + c)
                            if (v > maxCls) { maxCls = v; clsId = c - 5 }
                        }
                    }
                    val conf = obj * maxCls
                    if (conf < confThreshold) continue
                    candidates.add(
                        DetectionBox(
                            cx = buf.get(off),
                            cy = buf.get(off + 1),
                            width = buf.get(off + 2),
                            height = buf.get(off + 3),
                            confidence = conf,
                            classId = clsId,
                        )
                    )
                }

                // mask:解 4D output → 反 letterbox → 二值化 → 跟原图等大 ByteArray
                if (maskOutputIndex >= 0) {
                    textMask = try {
                        val maskTensor = outputs[maskOutputIndex] as OnnxTensor
                        decodeMaskToBitmapCoords(maskTensor, bitmap.width, bitmap.height, scale, padX, padY)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "CTD: mask decode failed, fallback to no-mask")
                        null
                    }
                }
            } finally {
                outputs.close()
            }
        } finally {
            inputTensor.close()
        }

        // class-agnostic NMS:text/balloon 互相覆盖时取高分
        coroutineContext.ensureActive()
        val kept = nms(candidates, iouThreshold)
        Timber.d(
            "CTD: ${candidates.size} candidates → ${kept.size} after NMS (conf>=$confThreshold, iou<=$iouThreshold)" +
                if (textMask != null) ", mask=${textMask.width}x${textMask.height}" else ", mask=null"
        )

        // 反 letterbox 到原图坐标
        val finalBoxes = kept.map { b ->
            DetectionBox(
                cx = (b.cx - padX) / scale,
                cy = (b.cy - padY) / scale,
                width = b.width / scale,
                height = b.height / scale,
                confidence = b.confidence,
                classId = b.classId,
            )
        }
        return DetectionResult(finalBoxes, textMask)
    }

    /**
     * 把 CTD 4D mask tensor (1, 1, maskH, maskW) 解到「原图坐标系」的 1-byte mask:
     *  1. mask tensor 是 letterbox 坐标系 — letterbox 尺寸 INPUT_SIZE,padding 全在右下(padX=padY=0)
     *  2. mask 可能比 INPUT_SIZE 低分辨率(常见 1024 但有些导出会下采样)→ 用比例反推
     *  3. 对原图每个像素 (bx, by) → letterbox 坐标 (bx*scale, by*scale) → mask 坐标 nearest sample
     *  4. 阈值 [MASK_THRESHOLD] 二值化(CTD seg head 是 sigmoid'd in [0,1])
     */
    private suspend fun decodeMaskToBitmapCoords(
        maskTensor: OnnxTensor,
        bmpW: Int, bmpH: Int,
        scale: Float, padX: Float, padY: Float,
    ): TextMask {
        val s = maskTensor.info.shape
        require(s.size == 4 && s[0] == 1L && s[1] == 1L) { "mask shape ${s.toList()} not (1,1,H,W)" }
        val mh = s[2].toInt()
        val mw = s[3].toInt()
        val src = maskTensor.floatBuffer

        // letterbox 坐标 → mask 坐标 比例。CTD 一般 mask 跟 INPUT_SIZE 同分辨率,这里通用化
        val mScaleX = mw.toFloat() / INPUT_SIZE
        val mScaleY = mh.toFloat() / INPUT_SIZE

        val out = ByteArray(bmpW * bmpH)
        var painted = 0
        for (by in 0 until bmpH) {
            // 逐行检查取消:mask 解到原图分辨率(几百万像素)也是秒级阻塞段
            coroutineContext.ensureActive()
            // letterboxY = by * scale + padY,本项目 padY=0 所以化简
            val lbY = by * scale + padY
            val my = (lbY * mScaleY).toInt().coerceIn(0, mh - 1)
            val myRow = my * mw
            val rowOff = by * bmpW
            for (bx in 0 until bmpW) {
                val lbX = bx * scale + padX
                val mx = (lbX * mScaleX).toInt().coerceIn(0, mw - 1)
                if (src.get(myRow + mx) > MASK_THRESHOLD) {
                    out[rowOff + bx] = 1
                    painted++
                }
            }
        }
        Timber.d("CTD: mask decoded → ${bmpW}x${bmpH}, $painted ink px (${"%.2f".format(painted * 100f / (bmpW * bmpH))}%)")
        return TextMask(bmpW, bmpH, out)
    }

    /**
     * Letterbox:保持长宽比缩放到目标尺寸,padding 全堆在 **右下**,填充色 **black**。
     * 跟上游 dmMaze utils/imgproc_utils.py 一致:
     *   `cv2.copyMakeBorder(im, 0, dh, 0, dw, BORDER_CONSTANT, value=(0,0,0))`
     *   即 top=0, left=0, 全部 padding 加到 bottom/right。
     *
     * 居中 padding 数学上反算也对(只要 padX/padY 是真实左/上 padding 量),但模型在训练时
     * 看的是 right-bottom padding 分布,居中会有轻微 distribution shift。这里对齐训练分布。
     */
    private data class LetterboxResult(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float, // 左侧 padding(对齐右下方案:= 0)
        val padY: Float, // 上侧 padding(对齐右下方案:= 0)
    )

    private fun letterbox(src: Bitmap, target: Int): LetterboxResult {
        val scale = minOf(target.toFloat() / src.width, target.toFloat() / src.height)
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(LETTERBOX_FILL_COLOR)
        val srcRect = Rect(0, 0, src.width, src.height)
        // top=0, left=0 — padding 全部堆右下
        val dstRect = RectF(0f, 0f, newW.toFloat(), newH.toFloat())
        canvas.drawBitmap(src, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
        return LetterboxResult(out, scale, padX = 0f, padY = 0f)
    }

    /** RGB / 255 → CHW float32。简单 [0,1] 归一化,跟 CTD 训练分布一致(无 ImageNet mean/std)。 */
    private fun bitmapToTensor(bitmap: Bitmap): FloatBuffer {
        val size = INPUT_SIZE
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val buf = FloatBuffer.allocate(3 * size * size)
        // R 通道
        for (p in pixels) buf.put(((p shr 16) and 0xFF) / 255f)
        // G 通道
        for (p in pixels) buf.put(((p shr 8) and 0xFF) / 255f)
        // B 通道
        for (p in pixels) buf.put((p and 0xFF) / 255f)
        buf.rewind()
        return buf
    }

    private fun nms(boxes: List<DetectionBox>, iouThreshold: Float): List<DetectionBox> {
        if (boxes.isEmpty()) return boxes
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<DetectionBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            val it = sorted.iterator()
            while (it.hasNext()) {
                if (iou(best, it.next()) > iouThreshold) it.remove()
            }
        }
        return kept
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val ax1 = a.cx - a.width / 2f
        val ay1 = a.cy - a.height / 2f
        val ax2 = a.cx + a.width / 2f
        val ay2 = a.cy + a.height / 2f
        val bx1 = b.cx - b.width / 2f
        val by1 = b.cy - b.height / 2f
        val bx2 = b.cx + b.width / 2f
        val by2 = b.cy + b.height / 2f
        val xL = maxOf(ax1, bx1)
        val yT = maxOf(ay1, by1)
        val xR = minOf(ax2, bx2)
        val yB = minOf(ay2, by2)
        if (xR <= xL || yB <= yT) return 0f
        val inter = (xR - xL) * (yB - yT)
        val areaA = (ax2 - ax1) * (ay2 - ay1)
        val areaB = (bx2 - bx1) * (by2 - by1)
        return inter / (areaA + areaB - inter)
    }
}
