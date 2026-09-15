package ceui.pixiv.chat.ui

import android.app.Application
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.sticker.StickerImageView
import ceui.pixiv.witstudio.theme.V3Palette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatStickerBubbleTest {
    @Test fun `recycled sticker replies lose bubble chrome and text messages restore it`() {
        val app = RuntimeEnvironment.getApplication()
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val config = Configuration(app.resources.configuration).apply { uiMode = night }
            val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme_Index0)
            val palette = V3Palette.from(context)
            for (sent in listOf(false, true)) {
                val holder = ChatMessageAdapter.BubbleHolder(LayoutInflater.from(context), FrameLayout(context),
                    if (sent) R.layout.chat_bubble_sent else R.layout.chat_bubble_received, sent)
                val message = ChatMessageEntity(localKey = "test", uid = if (sent) 1 else 2,
                    room = "global", ts = 1000, text = "message")
                fun bind(msg: ChatMessageEntity) = holder.bind(msg, null, message, false, 1,
                    palette, palette.primary, palette.primary, null, null)
                val bubble = holder.itemView.findViewById<LinearLayout>(R.id.bubble)
                val image = (0 until bubble.childCount).map(bubble::getChildAt).filterIsInstance<StickerImageView>().single()
                bind(message)
                assertNotNull(bubble.background)
                bind(message.copy(stickerId = 123, replyToCmid = "quoted", replyToUid = 2, replyToText = "quoted text"))
                assertNull(bubble.background)
                assertEquals(0f, bubble.elevation)
                assertEquals(0, bubble.paddingLeft + bubble.paddingTop + bubble.paddingRight + bubble.paddingBottom)
                assertEquals(View.VISIBLE, image.visibility)
                assertEquals((64 * context.resources.displayMetrics.density).toInt(), image.layoutParams.width)
                assertEquals(View.VISIBLE, holder.quoteView.visibility)
                assertNotEquals(android.graphics.Color.WHITE, holder.itemView.findViewById<TextView>(R.id.tv_quote_name).currentTextColor)
                bind(message)
                assertNotNull(bubble.background)
                assertTrue(bubble.paddingLeft > 0)
                assertTrue(bubble.elevation > 0)
                assertEquals(View.GONE, image.visibility)
                assertEquals(View.GONE, holder.quoteView.visibility)
            }
        }
    }
}
