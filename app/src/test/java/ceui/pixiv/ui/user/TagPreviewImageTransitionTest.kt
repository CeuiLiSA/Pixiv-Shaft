package ceui.pixiv.ui.user

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.provider.Settings
import android.widget.ImageView
import ceui.pixiv.ui.common.PreviewImageTransitionFactory
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.transition.TransitionFactory
import com.bumptech.glide.request.transition.DrawableCrossFadeTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUiAutomation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25, 26, 35], application = Application::class)
class TagPreviewImageTransitionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun memoryCacheNeverFades() {
        setAnimationScale(1f)
        val factory = PreviewImageTransitionFactory(context)
        val image = ColorDrawable(Color.RED)

        assertSame(image, deliver(factory, image, DataSource.MEMORY_CACHE))
    }

    @Test
    fun diskAndNetworkLoadsFadeWhenAnimationsAreEnabled() {
        setAnimationScale(1f)
        val factory = PreviewImageTransitionFactory(context)

        for (source in listOf(DataSource.DATA_DISK_CACHE, DataSource.RESOURCE_DISK_CACHE, DataSource.REMOTE)) {
            assertTrue(deliver(factory, ColorDrawable(Color.RED), source) is TransitionDrawable)
        }
    }

    @Test
    fun disabledAnimationsShowImmediately() {
        setAnimationScale(0f)
        val factory = PreviewImageTransitionFactory(context)

        for (source in listOf(DataSource.MEMORY_CACHE, DataSource.DATA_DISK_CACHE, DataSource.REMOTE)) {
            val image = ColorDrawable(Color.RED)
            assertSame(image, deliver(factory, image, source))
        }
    }
    @Test
    fun customDurationProducesShorterFadeForAvatars() {
        setAnimationScale(1f)
        val defaultFactory = PreviewImageTransitionFactory(context)
        val shortFactory = PreviewImageTransitionFactory(context, durationMs = 150)

        val defaultTransition = defaultFactory.build(DataSource.REMOTE, true) as DrawableCrossFadeTransition
        val shortTransition = shortFactory.build(DataSource.REMOTE, true) as DrawableCrossFadeTransition

        val durationField = DrawableCrossFadeTransition::class.java.getDeclaredField("duration").apply {
            isAccessible = true
        }

        assertEquals(300, durationField.getInt(defaultTransition))
        assertEquals(150, durationField.getInt(shortTransition))
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