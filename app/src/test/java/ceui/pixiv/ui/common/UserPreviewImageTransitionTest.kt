package ceui.pixiv.ui.common

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.provider.Settings
import android.widget.ImageView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.transition.TransitionFactory
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUiAutomation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25, 26, 35])
class UserPreviewImageTransitionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun disablingAnimationsDuringLoadShowsDiskAndNetworkImagesImmediately() {
        setAnimationScale(1f)
        val factory = UserPreviewTransitionFactory(context)
        setAnimationScale(0f)

        for (source in listOf(DataSource.DATA_DISK_CACHE, DataSource.RESOURCE_DISK_CACHE, DataSource.REMOTE)) {
            val image = ColorDrawable(Color.RED)
            assertSame(image, deliver(factory, image, source))
        }
    }

    @Test
    fun reEnablingAnimationsUsesTheSameFactoryForTheNextImage() {
        setAnimationScale(0f)
        val factory = UserPreviewTransitionFactory(context)
        val first = ColorDrawable(Color.RED)
        assertSame(first, deliver(factory, first, DataSource.REMOTE))

        setAnimationScale(1f)
        assertTrue(deliver(factory, ColorDrawable(Color.RED), DataSource.REMOTE) is TransitionDrawable)
    }

    @Test
    fun memoryCacheImagesRemainImmediateWithAnimationsEnabled() {
        setAnimationScale(1f)
        val image = ColorDrawable(Color.RED)

        assertSame(image, deliver(UserPreviewTransitionFactory(context), image, DataSource.MEMORY_CACHE))
    }

    @Test
    fun aNonZeroAnimationScaleStillAllowsTheFade() {
        setAnimationScale(0.5f)

        assertTrue(
            deliver(UserPreviewTransitionFactory(context), ColorDrawable(Color.RED), DataSource.REMOTE)
                is TransitionDrawable,
        )
    }

    private fun setAnimationScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        ShadowUiAutomation.setAnimationScaleCompat(scale)
    }

    private fun deliver(factory: TransitionFactory<Drawable>, image: Drawable, source: DataSource): Drawable {
        val view = ImageView(context).apply { setImageDrawable(ColorDrawable(Color.GRAY)) }
        DrawableImageViewTarget(view).onResourceReady(image, factory.build(source, true))
        return view.drawable
    }
}
