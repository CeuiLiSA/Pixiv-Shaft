package ceui.pixiv.chat.ui

import android.content.Intent
import android.view.View
import ceui.lisa.activities.ImageDetailActivity
import ceui.pixiv.api.Client
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.plaza.PlazaImage
import ceui.pixiv.plaza.ui.PlazaImageSource
import ceui.pixiv.session.SessionManager
import com.google.gson.Gson

internal object ChatImageViewer {
    const val DATA_TYPE = "chat_images"
    private const val IMAGES = "chat_images"
    private const val AUTHOR = "chat_image_author"
    private const val MESSAGE = "chat_image_message"
    private const val VIEWER = "chat_image_viewer"

    suspend fun images(author: Long, messageRef: String): List<PlazaImage> =
        Client.mediaAPI.chatAttachment(author, messageRef).attachments.mapNotNull { attachment ->
            val original = attachment.resourceList.firstOrNull {
                it.type == "original" && it.url.startsWith("https://")
            } ?: return@mapNotNull null
            PlazaImage(
                attachment.mediaId, attachment.width, attachment.height, attachment.contentType,
                original.url, original.expiresAt, attachment.resourceList,
            )
        }

    fun open(message: ChatMessageEntity, images: List<PlazaImage>, index: Int, thumbnail: View) {
        val messageRef = message.clientMsgId ?: return
        val location = IntArray(2).also(thumbnail::getLocationOnScreen)
        val context = thumbnail.context
        context.startActivity(Intent(context, ImageDetailActivity::class.java).apply {
            putExtra("dataType", DATA_TYPE)
            putExtra("index", index)
            putExtra(AUTHOR, message.uid)
            putExtra(MESSAGE, messageRef)
            putExtra(VIEWER, SessionManager.loggedInUid)
            putExtra(IMAGES, Gson().toJson(images))
            putExtra(ImageDetailActivity.EXTRA_ENTER_BOUNDS, intArrayOf(
                location[0], location[1], location[0] + thumbnail.width, location[1] + thumbnail.height,
            ))
        })
    }

    fun source(intent: Intent): PlazaImageSource? {
        val author = intent.getLongExtra(AUTHOR, 0)
        val viewer = intent.getLongExtra(VIEWER, 0)
        val messageRef = intent.getStringExtra(MESSAGE)
        if (author <= 0 || viewer <= 0 || messageRef == null) return null
        val initial = Gson().fromJson(intent.getStringExtra(IMAGES), Array<PlazaImage>::class.java)
            ?.toList().orEmpty()
        return PlazaImageSource(0, initial, viewer, refresh = { images(author, messageRef) })
    }
}
