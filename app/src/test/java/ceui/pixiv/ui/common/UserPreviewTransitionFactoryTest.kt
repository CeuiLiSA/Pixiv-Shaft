package ceui.pixiv.ui.common

import android.app.Application
import android.graphics.drawable.Drawable
import android.provider.Settings
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.request.transition.DrawableCrossFadeTransition
import com.bumptech.glide.request.transition.NoTransition
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUiAutomation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25, 26, 35], application = Application::class)
class UserPreviewTransitionFactoryTest {
    private val loadedSources = listOf(
        DataSource.LOCAL,
        DataSource.DATA_DISK_CACHE,
        DataSource.RESOURCE_DISK_CACHE,
        DataSource.REMOTE,
    )

    @Before
    fun setUp() = setAnimationScale(1f)

    @After
    fun tearDown() = setAnimationScale(1f)

    private fun setAnimationScale(scale: Float) {
        Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale,
        )
        ShadowUiAutomation.setAnimationScaleCompat(scale)
    }

    private fun factory() = UserPreviewTransitionFactory(RuntimeEnvironment.getApplication())

    @Test
    fun memoryCacheNeverFades() {
        val factory = factory()
        for (first in listOf(true, false)) {
            assertSame(NoTransition.get<Drawable>(), factory.build(DataSource.MEMORY_CACHE, first))
        }
    }

    @Test
    fun diskNetworkAndLocalLoadsFadeWhenAnimationsAreEnabled() {
        val factory = factory()
        for (source in loadedSources) {
            assertTrue(factory.build(source, true) is DrawableCrossFadeTransition)
        }
    }

    @Test
    fun disabledAnimationsNeverFade() {
        setAnimationScale(0f)
        val factory = factory()
        for (source in DataSource.values()) {
            assertSame(NoTransition.get<Drawable>(), factory.build(source, true))
        }
    }

    @Test
    fun disablingAnimationsWhileLoadingTakesEffectWhenTheImageIsReady() {
        val factory = factory()
        assertTrue(factory.build(DataSource.REMOTE, true) is DrawableCrossFadeTransition)

        setAnimationScale(0f)

        assertSame(NoTransition.get<Drawable>(), factory.build(DataSource.REMOTE, true))
    }

    @Test
    fun enablingAnimationsWhileLoadingTakesEffectWhenTheImageIsReady() {
        setAnimationScale(0f)
        val factory = factory()

        setAnimationScale(1f)

        assertTrue(factory.build(DataSource.REMOTE, true) is DrawableCrossFadeTransition)
    }
}
