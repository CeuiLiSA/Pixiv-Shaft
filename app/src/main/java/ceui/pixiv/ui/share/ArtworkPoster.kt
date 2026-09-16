package ceui.pixiv.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.PathParser
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.witstudio.dialog.WitDialog
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 作品详情页的「保存作品海报」入口。
 *
 * 海报不是把一组 Android View 临时拼起来再截图：不同手机的密度、字体缩放和窗口大小会让
 * 那种产物漂移。这里固定在 1440px 离屏 [Canvas] 上排版，下载源图后按目标尺寸采样，最终直接
 * 按用户当前的插画下载模板写入同一位置。因此屏幕怎么旋转、系统字体放多大，都不会改变
 * 相册里成品的比例和清晰度，用户自定义的目录和命名规则也会继续生效。
 */
fun Fragment.saveArtworkPoster(illust: Illust, pageIndex: Int = 0) {
    launchArtworkPosterExport(requireContext(), lifecycleScope, illust, pageIndex)
}

/** 图片二级详情页使用的同款入口。 */
fun FragmentActivity.saveArtworkPoster(illust: Illust, pageIndex: Int = 0) {
    launchArtworkPosterExport(this, lifecycleScope, illust, pageIndex)
}

private fun launchArtworkPosterExport(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    illust: Illust,
    pageIndex: Int,
) {
    val appContext = context.applicationContext
    val dialog = WitDialog.CustomDialogBuilder(context)
        .setLayout(R.layout.dialog_snapshot_loading)
        .setCancelable(false)
        .show()
    dialog.findViewById<android.widget.TextView>(R.id.loading_message)
        ?.setText(R.string.artwork_poster_generating)

    scope.launch {
        try {
            val uri = withContext(Dispatchers.IO) {
                ArtworkPosterExporter.export(appContext, illust, pageIndex)
            }
            dialog.dismiss()
            Common.showToast(appContext.getString(R.string.artwork_poster_saved))
            Timber.tag(TAG).i(
                "poster saved illust=%d page=%d uri=%s",
                illust.id, pageIndex, uri,
            )
        } catch (cancelled: CancellationException) {
            dialog.dismiss()
            throw cancelled
        } catch (error: Throwable) {
            dialog.dismiss()
            Timber.tag(TAG).e(error, "poster export failed illust=%d page=%d", illust.id, pageIndex)
            Common.showToast(
                appContext.getString(
                    R.string.artwork_poster_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            )
        }
    }
}

private const val TAG = "ArtworkPoster"

internal object ArtworkPosterExporter {

    fun export(context: Context, illust: Illust, pageIndex: Int): Uri {
        val safePage = pageIndex.coerceIn(0, (illust.page_count - 1).coerceAtLeast(0))
        val artworkUrl = IllustDownload.getUrl(
            illust,
            safePage,
            // Ugoira 的 original 是 zip；海报只需要静态封面。
            if (illust.isGif()) Params.IMAGE_RESOLUTION_LARGE else Params.IMAGE_RESOLUTION_ORIGINAL,
        ) ?: error(context.getString(R.string.artwork_poster_image_unavailable))

        var artwork: Bitmap? = null
        var avatar: Bitmap? = null
        var poster: Bitmap? = null
        try {
            artwork = loadBitmap(context, artworkUrl, MAX_ARTWORK_DECODE_SIZE)
            avatar = illust.user?.profile_image_urls?.findMaxSizeUrl()?.let { url ->
                runCatching { loadBitmap(context, url, MAX_AVATAR_DECODE_SIZE) }
                    .onFailure { Timber.tag(TAG).w(it, "avatar unavailable uid=%d", illust.user?.id ?: 0L) }
                    .getOrNull()
            }
            val metadata = ArtworkPosterMetadata(
                authorName = illust.user?.name?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.artwork_poster_unknown_author),
                authorHandle = illust.user?.account?.trim()?.removePrefix("@").orEmpty(),
                authorId = illust.user?.id ?: 0L,
                title = illust.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.artwork_poster_untitled),
                artworkId = illust.id,
                createdAt = illust.create_date,
                pageCount = illust.page_count.coerceAtLeast(1),
                viewCount = illust.total_view,
                bookmarkCount = illust.total_bookmarks,
            )
            poster = ArtworkPosterRenderer.render(
                artwork = artwork,
                avatar = avatar,
                metadata = metadata,
                typography = ArtworkPosterTypography.from(context),
            )

            // 目录和基础文件名完整沿用用户当前的插画模板；仅加 _share 避免覆盖原图。
            val handle = DownloadsRegistry.downloads.openDerived(
                DownloadItems.illustPoster(illust, safePage),
                POSTER_FILENAME_SUFFIX,
            ) ?: return Uri.EMPTY

            var committed = false
            try {
                handle.stream.use { stream ->
                    check(poster.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                        context.getString(R.string.artwork_poster_encode_failed)
                    }
                }
                handle.onFinish()
                committed = true
            } finally {
                if (!committed) {
                    runCatching { handle.stream.close() }
                    runCatching { handle.onAbort() }
                }
            }
            return handle.uri
        } finally {
            artwork?.recycle()
            avatar?.recycle()
            poster?.recycle()
        }
    }

    /**
     * 先让 BitmapFactory 只读尺寸，再用 2 的幂采样；禁止把一张上亿像素的原图完整解进堆里。
     * 最长边压到预算以内：作品图最多约 16MB，且 2048px 仍高于海报内图片的 1560px 上限。
     */
    private fun loadBitmap(context: Context, rawUrl: String, maxSide: Int): Bitmap {
        val model: Any = when {
            rawUrl.startsWith("content://") || rawUrl.startsWith("file://") -> Uri.parse(rawUrl)
            rawUrl.startsWith("/") -> File(rawUrl)
            else -> GlideUrlChild(rawUrl)
        }
        val target = Glide.with(context).asFile().load(model).submit()
        val file = try {
            target.get()
        } finally {
            Glide.with(context).clear(target)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image data" }

        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: error("Unable to decode image")
    }

    private const val MAX_ARTWORK_DECODE_SIZE = 2_048
    private const val MAX_AVATAR_DECODE_SIZE = 256
    private const val JPEG_QUALITY = 96
    private const val POSTER_FILENAME_SUFFIX = "_share"
}

internal data class ArtworkPosterMetadata(
    val authorName: String,
    val authorHandle: String,
    val authorId: Long,
    val title: String,
    val artworkId: Long,
    val createdAt: String?,
    val pageCount: Int,
    val viewCount: Int?,
    val bookmarkCount: Int?,
)

/**
 * 与 UserActivityV3 相同的 Montserrat 字重阶梯。资源字体缺失时仍有明确的系统字体降级，
 * 避免海报导出因为字体加载失败而中断。
 */
internal data class ArtworkPosterTypography(
    val medium: Typeface,
    val semiBold: Typeface,
    val bold: Typeface,
) {
    companion object {
        fun from(context: Context): ArtworkPosterTypography = ArtworkPosterTypography(
            medium = ResourcesCompat.getFont(context, R.font.montserrat_medium)
                ?: Typeface.create("sans-serif-medium", Typeface.NORMAL),
            semiBold = ResourcesCompat.getFont(context, R.font.montserrat_semi_bold)
                ?: Typeface.create("sans-serif", Typeface.BOLD),
            bold = ResourcesCompat.getFont(context, R.font.montserrat_bold)
                ?: Typeface.create("sans-serif", Typeface.BOLD),
        )

        fun system(): ArtworkPosterTypography = ArtworkPosterTypography(
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            semiBold = Typeface.create("sans-serif", Typeface.BOLD),
            bold = Typeface.create("sans-serif", Typeface.BOLD),
        )
    }
}

/**
 * 参考用户给出的社交卡片做的相机水印式海报：暖灰底、悬浮白卡、作者签名、圆角照片、日期和
 * 三枚克制的元数据。所有数字都是输出像素，不读取 displayMetrics，保证跨设备一模一样。
 */
internal object ArtworkPosterRenderer {

    fun render(
        artwork: Bitmap,
        avatar: Bitmap?,
        metadata: ArtworkPosterMetadata,
        locale: Locale = Locale.getDefault(),
        typography: ArtworkPosterTypography = ArtworkPosterTypography.system(),
    ): Bitmap {
        require(artwork.width > 0 && artwork.height > 0)

        val imageWidth = CONTENT_WIDTH.toFloat()
        val naturalImageHeight = imageWidth * artwork.height.toFloat() / artwork.width.toFloat()
        val imageHeight = naturalImageHeight
            .coerceIn(MIN_IMAGE_HEIGHT.toFloat(), MAX_IMAGE_HEIGHT.toFloat())
            .roundToInt()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_PRIMARY
            textSize = TITLE_SIZE
            typeface = typography.semiBold
            letterSpacing = TITLE_LETTER_SPACING
        }
        val titleLayout = textLayout(
            text = metadata.title,
            paint = titlePaint,
            width = CONTENT_WIDTH,
            maxLines = 2,
        )

        val headerTop = CARD_TOP + CARD_PADDING_TOP
        val titleTop = headerTop + AVATAR_SIZE + HEADER_TO_TITLE
        val imageTop = titleTop + max(titleLayout.height, TITLE_MIN_HEIGHT) + TITLE_TO_IMAGE
        val dateBaseline = imageTop + imageHeight + IMAGE_TO_DATE + DATE_SIZE.roundToInt()
        val metricsCenterY = dateBaseline + DATE_TO_METRICS
        val cardBottom = metricsCenterY + METRIC_ICON_SIZE / 2 + CARD_PADDING_BOTTOM
        val outputHeight = cardBottom + OUTER_BOTTOM

        val output = Bitmap.createBitmap(OUTPUT_WIDTH, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        drawBackground(canvas, outputHeight)
        drawCard(canvas, cardBottom)
        drawHeader(canvas, avatar, metadata, typography, headerTop)

        canvas.save()
        canvas.translate(CONTENT_LEFT.toFloat(), titleTop.toFloat())
        titleLayout.draw(canvas)
        canvas.restore()

        val imageRect = RectF(
            CONTENT_LEFT.toFloat(),
            imageTop.toFloat(),
            (CONTENT_LEFT + CONTENT_WIDTH).toFloat(),
            (imageTop + imageHeight).toFloat(),
        )
        drawRoundedBitmap(canvas, artwork, imageRect, IMAGE_RADIUS)
        drawFooter(
            canvas,
            metadata,
            locale,
            typography,
            dateBaseline.toFloat(),
            metricsCenterY.toFloat(),
        )
        return output
    }

    private fun drawBackground(canvas: Canvas, height: Int) {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(BACKGROUND_TOP, BACKGROUND_BOTTOM),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, OUTPUT_WIDTH.toFloat(), height.toFloat(), paint)
    }

    private fun drawCard(canvas: Canvas, cardBottom: Int) {
        val rect = RectF(
            CARD_LEFT.toFloat(),
            CARD_TOP.toFloat(),
            CARD_RIGHT.toFloat(),
            cardBottom.toFloat(),
        )
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(42f, 0f, 20f, SHADOW)
        }
        canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, shadow)
        canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
    }

    private fun drawHeader(
        canvas: Canvas,
        avatar: Bitmap?,
        metadata: ArtworkPosterMetadata,
        typography: ArtworkPosterTypography,
        headerTop: Int,
    ) {
        val avatarRect = RectF(
            CONTENT_LEFT.toFloat(),
            headerTop.toFloat(),
            (CONTENT_LEFT + AVATAR_SIZE).toFloat(),
            (headerTop + AVATAR_SIZE).toFloat(),
        )
        if (avatar != null) {
            drawCircleBitmap(canvas, avatar, avatarRect)
        } else {
            drawAvatarFallback(canvas, avatarRect, metadata.authorName)
        }
        canvas.drawCircle(
            avatarRect.centerX(), avatarRect.centerY(), avatarRect.width() / 2f - 1f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.TRANSPARENT
                style = Paint.Style.STROKE
                strokeWidth = 2f
                this.color = AVATAR_BORDER
            },
        )

        val textLeft = avatarRect.right + AVATAR_TO_TEXT
        val textWidth = CONTENT_LEFT + CONTENT_WIDTH - textLeft
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_PRIMARY
            textSize = AUTHOR_SIZE
            typeface = typography.semiBold
        }
        val author = TextUtils.ellipsize(
            metadata.authorName,
            TextPaint(namePaint),
            textWidth,
            TextUtils.TruncateAt.END,
        )
        canvas.drawText(author, 0, author.length, textLeft, headerTop + 45f, namePaint)

        val handle = metadata.authorHandle.takeIf { it.isNotBlank() }
            ?.let { "@$it · pixiv.net" }
            ?: metadata.authorId.takeIf { it > 0L }?.let { "pixiv.net/users/$it" }
            ?: "pixiv.net"
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_TERTIARY
            textSize = HANDLE_SIZE
            typeface = typography.medium
            letterSpacing = SUPPORTING_LETTER_SPACING
        }
        val shortHandle = TextUtils.ellipsize(
            handle,
            TextPaint(handlePaint),
            textWidth,
            TextUtils.TruncateAt.END,
        )
        canvas.drawText(shortHandle, 0, shortHandle.length, textLeft, headerTop + 93f, handlePaint)
    }

    private fun drawAvatarFallback(canvas: Canvas, rect: RectF, authorName: String) {
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                FALLBACK_AVATAR_START, FALLBACK_AVATAR_END,
                Shader.TileMode.CLAMP,
            )
        })
        val trimmedName = authorName.trim()
        val initial = if (trimmedName.isEmpty()) {
            "S"
        } else {
            val firstCodePoint = trimmedName.codePointAt(0)
            String(Character.toChars(firstCodePoint))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(initial, rect.centerX(), baseline, paint)
    }

    private fun drawFooter(
        canvas: Canvas,
        metadata: ArtworkPosterMetadata,
        locale: Locale,
        typography: ArtworkPosterTypography,
        dateBaseline: Float,
        metricsCenterY: Float,
    ) {
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_SECONDARY
            textSize = DATE_SIZE
            typeface = typography.medium
            letterSpacing = SUPPORTING_LETTER_SPACING
            fontFeatureSettings = "tnum"
        }
        canvas.drawText(formatDate(metadata.createdAt, metadata.artworkId, locale), CONTENT_LEFT.toFloat(), dateBaseline, datePaint)

        val metricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_PRIMARY
            textSize = METRIC_SIZE
            typeface = typography.bold
            letterSpacing = METRIC_LETTER_SPACING
            fontFeatureSettings = "tnum"
        }
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            color = TEXT_PRIMARY
            style = Paint.Style.FILL
        }

        val pageLabel = metadata.pageCount.toString()
        val viewLabel = formatCount(metadata.viewCount, locale)
        val bookmarkLabel = formatCount(metadata.bookmarkCount, locale)
        drawMetric(
            canvas, CONTENT_LEFT.toFloat(), metricsCenterY,
            { c, x, y -> drawPagesIcon(c, x, y, iconPaint) },
            pageLabel, metricPaint,
        )
        drawMetric(
            canvas,
            CONTENT_LEFT + CONTENT_WIDTH / 2f - metricWidth(viewLabel, metricPaint) / 2f,
            metricsCenterY,
            { c, x, y -> drawViewsIcon(c, x, y, iconPaint) },
            viewLabel, metricPaint,
        )
        drawMetric(
            canvas,
            CONTENT_LEFT + CONTENT_WIDTH - metricWidth(bookmarkLabel, metricPaint),
            metricsCenterY,
            { c, x, y -> drawHeartIcon(c, x, y, iconPaint) },
            bookmarkLabel, metricPaint,
        )
    }

    private fun metricWidth(label: String, paint: Paint): Float =
        METRIC_ICON_SIZE + METRIC_TEXT_GAP + paint.measureText(label)

    private fun drawMetric(
        canvas: Canvas,
        left: Float,
        centerY: Float,
        icon: (Canvas, Float, Float) -> Unit,
        label: String,
        textPaint: Paint,
    ) {
        icon(canvas, left + METRIC_ICON_SIZE / 2f, centerY)
        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, left + METRIC_ICON_SIZE + METRIC_TEXT_GAP, baseline, textPaint)
    }

    private fun drawPagesIcon(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        drawVectorIcon(canvas, cx, cy, PAGES_PATH, paint)
    }

    private fun drawViewsIcon(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        drawVectorIcon(canvas, cx, cy, VIEWS_PATH, paint)
    }

    private fun drawHeartIcon(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        drawVectorIcon(canvas, cx, cy, HEART_PATH, paint)
    }

    private fun drawVectorIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        pathData: String,
        paint: Paint,
    ) {
        val path = PathParser.createPathFromPathData(pathData) ?: return
        val scale = METRIC_ICON_SIZE / ICON_VIEWPORT
        path.transform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(cx - METRIC_ICON_SIZE / 2f, cy - METRIC_ICON_SIZE / 2f)
        })
        canvas.drawPath(path, paint)
    }

    private const val PAGES_PATH =
        "M20,2H8C6.9,2 6,2.9 6,4V16C6,17.1 6.9,18 8,18H20C21.1,18 22,17.1 22,16V4C22,2.9 21.1,2 20,2ZM20,16H8V4H20V16ZM4,6H2V20C2,21.1 2.9,22 4,22H18V20H4V6Z"
    private const val VIEWS_PATH =
        "M12,4.5C7,4.5 2.73,7.61 1,12C2.73,16.39 7,19.5 12,19.5C17,19.5 21.27,16.39 23,12C21.27,7.61 17,4.5 12,4.5ZM12,17C9.24,17 7,14.76 7,12C7,9.24 9.24,7 12,7C14.76,7 17,9.24 17,12C17,14.76 14.76,17 12,17ZM12,9C10.34,9 9,10.34 9,12C9,13.66 10.34,15 12,15C13.66,15 15,13.66 15,12C15,10.34 13.66,9 12,9Z"
    private const val HEART_PATH =
        "M16.5,3C14.76,3 13.09,3.81 12,5.09C10.91,3.81 9.24,3 7.5,3C4.42,3 2,5.42 2,8.5C2,12.28 5.4,15.36 10.55,20.04L12,21.35L13.45,20.03C18.6,15.36 22,12.28 22,8.5C22,5.42 19.58,3 16.5,3ZM12.1,18.55L12,18.65L11.9,18.55C7.14,14.24 4,11.39 4,8.5C4,6.5 5.5,5 7.5,5C9.04,5 10.54,5.99 11.07,7.36H12.94C13.46,5.99 14.96,5 16.5,5C18.5,5 20,6.5 20,8.5C20,11.39 16.86,14.24 12.1,18.55Z"
    private const val ICON_VIEWPORT = 24f

    private fun drawCircleBitmap(canvas: Canvas, bitmap: Bitmap, rect: RectF) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val dx = rect.centerX() - bitmap.width * scale / 2f
        val dy = rect.centerY() - bitmap.height * scale / 2f
        shader.setLocalMatrix(android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
        })
    }

    private fun drawRoundedBitmap(canvas: Canvas, bitmap: Bitmap, rect: RectF, radius: Float) {
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height
        val rectRatio = rect.width() / rect.height()
        val source = if (bitmapRatio > rectRatio) {
            val sourceWidth = bitmap.height * rectRatio
            val left = (bitmap.width - sourceWidth) / 2f
            Rect(left.roundToInt(), 0, (left + sourceWidth).roundToInt(), bitmap.height)
        } else {
            val sourceHeight = bitmap.width / rectRatio
            val top = (bitmap.height - sourceHeight) / 2f
            Rect(0, top.roundToInt(), bitmap.width, (top + sourceHeight).roundToInt())
        }
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        canvas.drawBitmap(bitmap, source, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        })
        canvas.restore()
    }

    private fun textLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(width)
            .setMaxLines(maxLines)
            .setLineSpacing(4f, 1f)
            .build()

    private fun formatDate(raw: String?, artworkId: Long, locale: Locale): String {
        val date = raw?.let {
            runCatching { OffsetDateTime.parse(it).toZonedDateTime() }.getOrNull()
        }
        if (date == null) return "PIXIV · #$artworkId"
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(date)
    }

    internal fun formatCount(value: Int?, locale: Locale): String {
        val safe = value?.coerceAtLeast(0) ?: 0
        if (safe < 10_000) return safe.toString()
        val eastAsian = locale.language == Locale.CHINESE.language || locale.language == Locale.JAPANESE.language
        if (eastAsian) return compact(safe / 10_000.0, "万")
        if (safe < 1_000_000) return compact(safe / 1_000.0, "K")
        return compact(safe / 1_000_000.0, "M")
    }

    private fun compact(number: Double, suffix: String): String {
        val rounded = (number * 10).roundToInt() / 10.0
        val body = if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else "%.1f".format(Locale.US, rounded)
        return body + suffix
    }

    private const val OUTPUT_WIDTH = 1_440
    private const val CARD_LEFT = 174
    private const val CARD_RIGHT = 1_266
    private const val CARD_TOP = 164
    private const val OUTER_BOTTOM = 184
    private const val CARD_PADDING_TOP = 56
    private const val CARD_PADDING_BOTTOM = 68
    private const val CONTENT_LEFT = 226
    private const val CONTENT_WIDTH = 988
    private const val AVATAR_SIZE = 112
    private const val AVATAR_TO_TEXT = 30f
    private const val HEADER_TO_TITLE = 32
    private const val TITLE_TO_IMAGE = 32
    private const val TITLE_MIN_HEIGHT = 56
    private const val MIN_IMAGE_HEIGHT = 556
    private const val MAX_IMAGE_HEIGHT = 1_560
    private const val IMAGE_TO_DATE = 54
    private const val DATE_TO_METRICS = 92
    private const val METRIC_ICON_SIZE = 50
    private const val METRIC_TEXT_GAP = 16f

    private const val CARD_RADIUS = 48f
    private const val IMAGE_RADIUS = 28f
    private const val AUTHOR_SIZE = 47f
    private const val HANDLE_SIZE = 29f
    private const val TITLE_SIZE = 46f
    private const val DATE_SIZE = 31f
    private const val METRIC_SIZE = 38f
    private const val TITLE_LETTER_SPACING = -0.01f
    private const val SUPPORTING_LETTER_SPACING = 0.015f
    private const val METRIC_LETTER_SPACING = -0.015f

    // UserActivityV3 / Wit Studio 的三级文字 token；透明度保留，让白卡自然完成混色。
    private val TEXT_PRIMARY = Color.rgb(26, 26, 46)
    private val TEXT_SECONDARY = Color.argb(0x99, 26, 26, 46)
    private val TEXT_TERTIARY = Color.argb(0x55, 26, 26, 46)
    private val BACKGROUND_TOP = Color.rgb(247, 247, 245)
    private val BACKGROUND_BOTTOM = Color.rgb(239, 240, 237)
    private val SHADOW = Color.argb(35, 0, 0, 0)
    private val AVATAR_BORDER = Color.argb(24, 0, 0, 0)
    private val FALLBACK_AVATAR_START = Color.rgb(93, 185, 255)
    private val FALLBACK_AVATAR_END = Color.rgb(112, 102, 241)
}
