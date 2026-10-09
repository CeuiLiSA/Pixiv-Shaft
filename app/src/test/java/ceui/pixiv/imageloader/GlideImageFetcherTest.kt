package ceui.pixiv.imageloader

import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.engine.GlideException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException

class GlideImageFetcherTest {

    /** RequestFutureTarget.get() 失败时的真实形状：ExecutionException → GlideException → causes 列表。 */
    private fun glideFailure(root: Throwable): Throwable = ExecutionException(
        GlideException(
            "Failed to load resource",
            listOf<Throwable>(GlideException("Fetching data failed", root)),
        )
    )

    @Test
    fun `read timeout under glide wrapping is recognized`() {
        assertTrue(GlideImageFetcher.isReadTimeout(glideFailure(SocketTimeoutException("Read timed out"))))
        assertTrue(GlideImageFetcher.isReadTimeout(glideFailure(SocketTimeoutException("timeout"))))
    }

    @Test
    fun `non read timeout failures under glide wrapping are not retried`() {
        assertFalse(GlideImageFetcher.isReadTimeout(glideFailure(HttpException("Forbidden", 403))))
        assertFalse(
            GlideImageFetcher.isReadTimeout(
                glideFailure(SocketTimeoutException("failed to connect to x after 10000ms"))
            )
        )
    }
}
