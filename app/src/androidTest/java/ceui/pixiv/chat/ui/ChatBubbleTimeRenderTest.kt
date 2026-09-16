package ceui.pixiv.chat.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.R
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.SendState
import ceui.pixiv.sticker.StickerImageView
import ceui.pixiv.witstudio.theme.V3Palette
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android text measurement and production holders; sends no messages. */
@RunWith(AndroidJUnit4::class)
class ChatBubbleTimeRenderTest {
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
                        if (message.stickerId == null) {
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
