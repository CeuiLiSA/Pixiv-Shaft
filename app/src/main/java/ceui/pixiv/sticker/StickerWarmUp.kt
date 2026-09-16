package ceui.pixiv.sticker

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-start entry point for the sticker installation.
 *
 * The contract is deliberately narrow: **a device with no installation must come out of
 * this untouched** - no metadata request, no ZIP download, no state change. Stickers are
 * hundreds of MB and nobody asked for them at launch.
 *
 * A device that already has one gets the opposite treatment: the ~5s full SHA-256/CRC32
 * verification a panel open used to pay for is folded into a read-only revalidation that
 * runs here, off the main thread, so the first tap on the panel is instant.
 */
object StickerWarmUp {

    /** One warm-up per process; the deferred-init hook is idempotent too, this is belt and braces. */
    private val fired = AtomicBoolean(false)

    @JvmStatic
    fun trigger() {
        if (!fired.compareAndSet(false, true)) return
        StickerRepository.warmUp()
    }
}
