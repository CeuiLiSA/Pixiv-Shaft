package ceui.pixiv.plaza.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.R
import ceui.pixiv.plaza.*
import com.bumptech.glide.Glide
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Renders production views with Figma fixtures locally; never publishes a test post. */
@RunWith(AndroidJUnit4::class)
class PlazaFigmaRenderTest {
    @Test
    fun renderNineImagesWithDynamicDayNightPalette() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val photos =
            (0..8).map { index ->
                assets.open("plaza-figma/imgGaleryImg${if(index==0) "" else index}.png").use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = 2 },
                    )!!
                }
            }
        val avatar =
            assets.open("plaza-figma/imgEllipse2690.png").use { BitmapFactory.decodeStream(it)!! }
        try {
            for (dark in listOf(false, true)) for (detail in listOf(false, true)) instrumentation
                .runOnMainSync {
                    val configuration =
                        Configuration(target.resources.configuration).apply {
                            densityDpi = 160
                            fontScale = 1f
                            uiMode =
                                if (dark) Configuration.UI_MODE_NIGHT_YES
                                else Configuration.UI_MODE_NIGHT_NO
                        }
                    val context =
                        ContextThemeWrapper(
                            target.createConfigurationContext(configuration),
                            R.style.AppTheme,
                        )
                    val post =
                        PlazaPost(
                            1,
                            42,
                            "Ayaba Onile-Ire",
                            "Route Overview\nTotal Distance: ~1,800 km Recommended Duration: 12–14 days",
                            1778371200000,
                            null,
                            null,
                            null,
                            0,
                            8,
                            true,
                            (0..8).map {
                                PlazaImage("$it", 960, 1200, "image/jpeg", "", Long.MAX_VALUE)
                            },
                            title = "New Zealand South Island Road Trip Itinerary",
                            reactions =
                                listOf(
                                    PlazaReaction("👀", 38, true),
                                    PlazaReaction("💪", 19, false),
                                    PlazaReaction("👌", 32, false),
                                    PlazaReaction("😂", 2, false),
                                    PlazaReaction("🤔", 6, false),
                                ),
                            commentsPreview =
                                listOf(
                                    PlazaCommentPreview(
                                        2,
                                        50,
                                        "Brian",
                                        "12 days vibe hits different 🚐 No rush and plenty of stops",
                                    ),
                                    PlazaCommentPreview(
                                        3,
                                        51,
                                        "Bojan",
                                        "Autumn views are chef's kiss 🍂 Saved this route!",
                                    ),
                                ),
                        )
                    val view = PostView(context, { 42L })
                    view.bind(post, false, detail, {}, {}, { _, _, _ -> })
                    val width = context.dp(390)
                    fun layout() {
                        view.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                        view.layout(0, 0, width, view.measuredHeight)
                    }
                    layout()
                    val grid =
                        (0 until view.childCount)
                            .map(view::getChildAt)
                            .filterIsInstance<PlazaIllustGrid>()
                            .single()
                    grid.onMeasured?.invoke(grid.width)
                    layout()
                    assertEquals(3, grid.childCount)
                    for (row in 0..2) for (column in 0..2) {
                        val image =
                            (grid.getChildAt(row) as ViewGroup).getChildAt(column) as ImageView
                        Glide.with(image).clear(image)
                        image.setImageBitmap(photos[row * 3 + column])
                    }
                    val author = (view.getChildAt(0) as ViewGroup).getChildAt(0) as ImageView
                    Glide.with(author).clear(author)
                    author.setImageBitmap(avatar)
                    val screen =
                        android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setBackgroundColor(
                                androidx.core.content.ContextCompat.getColor(context, R.color.v3_bg)
                            )
                        }
                    android.view.LayoutInflater.from(context)
                        .inflate(R.layout.toolbar_layout, screen, true)
                    screen.findViewById<android.widget.TextView>(R.id.toolbar_title).text =
                        if (detail) "帖子详情" else "广场"
                    screen.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                        .menu.add(if (detail) "更多" else "发帖")
                        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                    screen.addView(view)
                    if (detail) {
                        screen.addView(
                            PostView(context, { 42L })
                                .apply {
                                    bind(
                                        post.copy(
                                            id = 2,
                                            displayName = "Gabrielle Roth",
                                            title = "",
                                            text =
                                                "This route looks absolutely dreamy! 1800km over two weeks sounds like the perfect slow travel pace, no rushing between spots.",
                                            images = emptyList(),
                                            commentsPreview = emptyList(),
                                            reactions = emptyList(),
                                        ),
                                        false,
                                        false,
                                        {},
                                        {},
                                        { _, _, _ -> },
                                        comment = true,
                                    )
                                }
                        )
                        screen.addView(PlazaReplyBar(context))
                    }
                    screen.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                    screen.layout(0, 0, width, screen.measuredHeight)
                    val output =
                        Bitmap.createBitmap(width, screen.measuredHeight, Bitmap.Config.ARGB_8888)
                    screen.draw(Canvas(output))
                    File(
                            target.getExternalFilesDir(null),
                            "plaza-figma-${if(detail) "detail" else "feed"}-${if(dark) "dark" else "light"}.png",
                        )
                        .outputStream()
                        .use {
                            assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    assertTrue(view.measuredHeight > 500)
                    view.clear()
                    output.recycle()
                }
        } finally {
            photos.forEach { it.recycle() }
            avatar.recycle()
        }
    }
}
