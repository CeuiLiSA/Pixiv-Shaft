package ceui.pixiv.ui.recommend

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ImageView
import ceui.lisa.R
import ceui.lisa.databinding.CellSeriesV3Binding
import ceui.pixiv.ui.common.PreviewImageTransitionFactory
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.series.SeriesCard
import ceui.pixiv.ui.series.SeriesCardModel
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.transition.TransitionFactory
import org.junit.Assert.assertNotNull
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
class ArtistRankAndSeriesTransitionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun artistRankEnablesFadePreviewImagesOnLoad() {
        val field = UserFeedFragment::class.java.getDeclaredField("fadePreviewImagesOnLoad").apply {
            isAccessible = true
        }

        assertTrue(
            "ArtistRankFeedFragment default constructor must have fadePreviewImagesOnLoad = true",
            field.getBoolean(ArtistRankFeedFragment())
        )
        assertTrue(
            "ArtistRankFeedFragment.newInstance('total') must have fadePreviewImagesOnLoad = true",
            field.getBoolean(ArtistRankFeedFragment.newInstance("total"))
        )
        assertTrue(
            "ArtistRankFeedFragment.newInstance('avg') must have fadePreviewImagesOnLoad = true",
            field.getBoolean(ArtistRankFeedFragment.newInstance("avg"))
        )
    }

    @Test
    fun seriesCardBindAttachesCoverRequest() {
        context.setTheme(R.style.AppTheme)
        val binding = CellSeriesV3Binding.inflate(LayoutInflater.from(context))
        SeriesCard.setup(binding)
        val model = SeriesCardModel(
            title = "Test Series",
            coverUrl = "https://i.pximg.net/c/240x480/custom-thumb.jpg",
            countText = "10话",
            subtitle = "累计收藏 100",
            subtitleAccent = true,
            authorName = "Author",
            authorHeadUrl = "https://i.pximg.net/user-profile.jpg",
            rank = 1,
        )
        val transition =
            DrawableTransitionOptions().transition(PreviewImageTransitionFactory(context))
        SeriesCard.bind(binding, model, Glide.with(context), coverTransition = transition)

        val request = DrawableImageViewTarget(binding.cover).request
        assertNotNull("Cover request must be bound to ImageView", request)
    }

    @Test
    fun previewImageTransitionFadesRemoteLoadsWhenAnimationsEnabled() {
        setAnimationScale(1f)
        val factory = PreviewImageTransitionFactory(context)
        val image = ColorDrawable(Color.RED)

        // Memory cache must show immediately without fading
        assertSame(image, deliver(factory, image, DataSource.MEMORY_CACHE))

        // Remote load must produce TransitionDrawable
        assertTrue(deliver(factory, image, DataSource.REMOTE) is TransitionDrawable)
    }

    @Test
    fun previewImageTransitionShowsImmediatelyWhenAnimationsDisabled() {
        setAnimationScale(0f)
        val factory = PreviewImageTransitionFactory(context)
        val image = ColorDrawable(Color.RED)

        assertSame(image, deliver(factory, image, DataSource.REMOTE))
        assertSame(image, deliver(factory, image, DataSource.MEMORY_CACHE))
    }

    private fun setAnimationScale(scale: Float) {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale
        )
        ShadowUiAutomation.setAnimationScaleCompat(scale)
    }

    private fun deliver(
        factory: TransitionFactory<Drawable>,
        image: Drawable,
        source: DataSource
    ): Drawable {
        val view = ImageView(context).apply { setImageDrawable(ColorDrawable(Color.GRAY)) }
        DrawableImageViewTarget(view).onResourceReady(image, factory.build(source, true))
        return view.drawable
    }
}
