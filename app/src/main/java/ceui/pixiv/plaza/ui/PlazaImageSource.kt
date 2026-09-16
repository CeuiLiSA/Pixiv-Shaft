package ceui.pixiv.plaza.ui

import android.content.Context
import ceui.lisa.R
import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaImage
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.engine.GlideException
import java.io.File
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Image bytes only; shared viewer owns zoom, paging, transitions and lifecycle. */
internal class PlazaImageSource(
    private val postId: Long,
    initial: List<PlazaImage>,
    private val owner: Long,
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
    private val refresh: suspend () -> List<PlazaImage> = {
        PlazaRepository.api.post(postId).images
    },
) {
    private val mediaIds = initial.map { it.mediaId }
    private var images = initial.associateBy { it.mediaId }
    private val mutex = Mutex()
    val size
        get() = mediaIds.size

    private fun requireAccount() {
        if (owner <= 0 || owner != currentUid())
            throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }

    internal suspend fun resolve(index: Int, rejectedUrl: String? = null): PlazaImage =
        mutex.withLock {
            requireAccount()
            val id = mediaIds[index]
            var image = images[id] ?: throw PlazaFailure(PlazaMessage(R.string.plaza_no_images))
            if (image.expiresAt <= System.currentTimeMillis() + 5000 || image.url == rejectedUrl) {
                val updated = refresh()
                requireAccount()
                images = updated.associateBy { it.mediaId }
                image = images[id] ?: throw PlazaFailure(PlazaMessage(R.string.plaza_no_images))
            }
            image
        }

    suspend fun file(context: Context, index: Int): File {
        var image = resolve(index)
        return try {
                fetch(context, image)
            } catch (error: ExecutionException) {
                val denied =
                    (error.cause as? GlideException)?.rootCauses?.any {
                        it is HttpException && it.statusCode in listOf(401, 403)
                    } == true
                if (!denied) throw error
                // At most one refresh/retry; adjacent pages share the refreshed signatures.
                image = resolve(index, image.url)
                fetch(context, image)
            }
            .also { requireAccount() }
    }

    private suspend fun fetch(context: Context, image: PlazaImage): File =
        runInterruptible(Dispatchers.IO) {
            val target =
                Glide.with(context.applicationContext)
                    .asFile()
                    .load(PlazaMediaUrl(image, owner))
                    .submit()
            try {
                target.get()
            } finally {
                target.cancel(true)
            }
        }
}
