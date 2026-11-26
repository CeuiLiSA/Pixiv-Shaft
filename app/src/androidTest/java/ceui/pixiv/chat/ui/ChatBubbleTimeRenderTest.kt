package ceui.pixiv.chat.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.R
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.SendState
import ceui.pixiv.sticker.StickerImageView
import ceui.pixiv.witstudio.theme.V3Palette
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android text measurement and production holders; sends no messages. */
@RunWith(AndroidJUnit4::class)
class ChatBubbleTimeRenderTest {
    @Test
    fun photoPlaceholderRendersInBothThemesAndReturnsWhenDrawableClears() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val colors = mutableListOf<Int>()
        for (dark in listOf(false, true)) {
            instrumentation.runOnMainSync {
                val config = Configuration(target.resources.configuration).apply {
                    uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(target.createConfigurationContext(config), R.style.AppTheme)
                val album = ChatAttachmentView(context)
                album.bind(ChatMessageEntity(localKey = "loading-photo", uid = 1, room = "global",
                    ts = 1_789_524_660_000, hasAttachment = true))
                val density = context.resources.displayMetrics.density
                val width = (240 * density).roundToInt()
                album.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                album.layout(0, 0, width, album.measuredHeight)
                assertEquals((width / 1.5f).roundToInt(), album.height)
                val cell = album.getChildAt(0) as ImageView
                val bitmap = Bitmap.createBitmap(album.width, album.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val pageColor = ContextCompat.getColor(context, R.color.v3_bg)
                val outline = Path().apply {
                    val radius = (16 * density).roundToInt().toFloat()
                    addRoundRect(0f, 0f, album.width.toFloat(), album.height.toFloat(), radius, radius, Path.Direction.CW)
                }
                fun drawSample(): Int {
                    bitmap.eraseColor(pageColor)
                    canvas.save()
                    canvas.clipPath(outline)
                    album.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    album.layout(0, 0, width, album.measuredHeight)
                    album.draw(canvas)
                    canvas.restore()
                    return bitmap.getPixel(width / 4, album.height / 4)
                }
                val loadingColor = drawSample()
                colors += loadingColor
                assertNotEquals(pageColor, loadingColor)
                assertEquals(255, Color.alpha(loadingColor))
                if (InstrumentationRegistry.getArguments().getString("captureTestAlbum") == "true") {
                    val mode = if (dark) "dark" else "light"
                    File(target.getExternalFilesDir(null), "chat-placeholder-$mode.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                cell.setImageDrawable(ColorDrawable(Color.MAGENTA))
                assertEquals(Color.MAGENTA, drawSample())
                cell.setImageDrawable(null)
                assertEquals(loadingColor, drawSample())
                bitmap.recycle()
            }
        }
        assertTrue(ColorUtils.calculateLuminance(colors[0]) > ColorUtils.calculateLuminance(colors[1]))
    }

    @Test
    fun clockFitsInsideBubbleWithoutCoveringTextAcrossThemesWidthsAndFontScales() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        for (dark in listOf(false, true)) for (width in listOf(320, 390, 720)) for (scale in listOf(1f, 2f)) {
            instrumentation.runOnMainSync {
                val config = Configuration(target.resources.configuration).apply {
                    densityDpi = 160
                    fontScale = scale
                    uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(target.createConfigurationContext(config), R.style.AppTheme)
                val column = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(ContextCompat.getColor(context, R.color.v3_bg))
                }
                val palette = V3Palette.from(context)
                for (sent in listOf(false, true)) {
                    val base = ChatMessageEntity(localKey = "preview", uid = if (sent) 1 else 2,
                        room = "global", ts = 1_789_524_660_000, displayName = "Alice", text = "你好")
                    val samples = listOf(
                        base,
                        base.copy(text = "时间放在气泡里面，长消息末行空间不够时自动换行。"),
                        base.copy(text = "末行还有空位\nHi"),
                        base.copy(text = "回复内容", replyToCmid = "quoted", replyToUid = 2,
                            replyToDisplayName = "Alice", replyToText = "时间可以放在气泡内吧，像 Telegram 那样"),
                        base.copy(text = "Failed message", state = SendState.Failed),
                        base.copy(text = "https://example.com/long-link\n链接和时间不会相互遮挡"),
                        base.copy(text = "مرحبا بالعالم"),
                        base.copy(text = "", stickerId = 123),
                        base.copy(text = "😀"),
                        base.copy(text = "[图片]", hasAttachment = true),
                    )
                    for ((index, message) in samples.withIndex()) {
                        val holder = ChatMessageAdapter.BubbleHolder(LayoutInflater.from(context), column,
                            if (sent) R.layout.chat_bubble_sent else R.layout.chat_bubble_received, sent)
                        holder.bind(message, null, base, false, 1, palette, palette.primary, palette.primary, null, null)
                        val row = holder.itemView
                        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        row.layout(0, 0, width, row.measuredHeight)
                        val bubble = row.findViewById<View>(R.id.bubble)
                        val body = row.findViewById<ChatMessageBodyLayout>(R.id.message_body)
                        val meta = row.findViewById<View>(R.id.message_meta)
                        val text = row.findViewById<TextView>(R.id.tv_content)
                        val label = "dark=$dark width=$width scale=$scale sent=$sent sample=$index"
                        assertTrue(label, meta.left >= 0 && meta.right <= body.width && meta.bottom <= body.height)
                        assertTrue(label, body.top + meta.bottom <= bubble.height - bubble.paddingBottom)
                        if (message.hasAttachment) {
                            val attachment = (0 until body.childCount).map(body::getChildAt)
                                .filterIsInstance<ChatAttachmentView>().single()
                            assertEquals(label, View.GONE, text.visibility)
                            assertTrue(label, attachment.bottom <= meta.top)
                            assertTrue(label, attachment.clipToOutline)
                            assertFalse(label, attachment.getChildAt(0).clipToOutline)
                        } else if (message.stickerId == null) {
                            val last = text.layout.lineCount - 1
                            if (meta.top < text.bottom) {
                                assertTrue(label, text.layout.getParagraphDirection(last) == 1)
                                assertTrue(label, text.layout.getLineRight(last) + text.totalPaddingLeft < meta.left)
                            }
                            if (index == 0 && scale == 1f) assertTrue(label, meta.top < text.bottom)
                            assertEquals(message.text, text.text.toString())
                        } else {
                            val image = (0 until body.childCount).map(body::getChildAt)
                                .filterIsInstance<StickerImageView>().single()
                            assertTrue(label, image.right <= meta.left || image.bottom <= meta.top)
                            image.setImageResource(R.drawable.chat_ic_emoji)
                        }
                        column.addView(row)
                    }
                }
                column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                column.layout(0, 0, width, column.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, column.height, Bitmap.Config.ARGB_8888)
                column.draw(Canvas(bitmap))
                File(target.getExternalFilesDir(null), "chat-time-$dark-$width-$scale.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }
}
