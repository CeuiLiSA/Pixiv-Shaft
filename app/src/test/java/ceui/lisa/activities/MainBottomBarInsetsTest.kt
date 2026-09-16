package ceui.lisa.activities

import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.ActivityCoverBinding
import ceui.pixiv.ui.slideshow.SlideshowFragment
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.Utils
import com.google.android.material.behavior.HideViewOnScrollBehavior
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 29, 30, 35])
class MainBottomBarInsetsTest {
    private lateinit var binding: ActivityCoverBinding
    private var originalBrokenDispatch: Boolean? = null
    private var rootDispatches = 0

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        binding = ActivityCoverBinding.inflate(LayoutInflater.from(context))
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        if (Build.VERSION.SDK_INT >= 30) {
            // Robolectric initializes this global flag with a framework context. Match the
            // platform's targetSdk check so API 30 exercises the app's native inset dispatch.
            originalBrokenDispatch = ReflectionHelpers.getStaticField(View::class.java, "sBrokenInsetsDispatch")
            ReflectionHelpers.setStaticField(View::class.java, "sBrokenInsetsDispatch",
                context.applicationInfo.targetSdkVersion < 30)
            // The home fix must leave an existing platform root listener untouched on API 30+.
            binding.root.setOnApplyWindowInsetsListener { _, insets ->
                rootDispatches++
                insets
            }
        }
        // Exercise the real home layout/setup without starting login, network or native storage.
        ReflectionHelpers.setField(activity, "baseBind", binding)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "setUpAutoHidingBottomBar")
        binding.navigationView.menu.add("Home")
    }

    @After
    fun tearDown() {
        originalBrokenDispatch?.let {
            ReflectionHelpers.setStaticField(View::class.java, "sBrokenInsetsDispatch", it)
        }
    }

    @Test
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    fun `content bottom inset does not inflate the bottom bar on repeated layouts`() {
        val context = binding.root.context
        val contentBottoms = IntArray(3)
        // Keep both ViewPagers' real listeners, including the nested pager used by home tabs.
        val nestedPager = ViewPager(context)
        binding.viewPager.addView(nestedPager)
        repeat(3) { index ->
            val page = FrameLayout(context)
            (if (index < 2) nestedPager else binding.viewPager).addView(page)
            ViewCompat.setOnApplyWindowInsetsListener(page) { _, insets ->
                contentBottoms[index] = insets.systemWindowInsetBottom
                insets
            }
        }

        var drawerBottom = 0
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { _, insets ->
            drawerBottom = insets.systemWindowInsetBottom
            insets
        }
        val systemBottom = 48
        // On API 28 a Builder seeded from CONSUMED retains the consumed flags. Model a
        // real system dispatch with the legacy non-consumed WindowInsets(Rect) constructor.
        val seedInsets = ReflectionHelpers.callConstructor(WindowInsets::class.java,
            ReflectionHelpers.ClassParameter.from(Rect::class.java, Rect(0, 24, 0, systemBottom)))
        val systemInsets = WindowInsetsCompat.Builder(WindowInsetsCompat.toWindowInsetsCompat(seedInsets))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, systemBottom))
            .setVisible(WindowInsetsCompat.Type.systemBars(), true)
            .build()
        val initialPadding = binding.navigationView.paddingBottom

        fun layout() {
            binding.root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            binding.root.layout(0, 0, 1080, 1920)
        }

        layout()
        binding.root.dispatchApplyWindowInsets(systemInsets.toWindowInsets()!!)
        layout()
        val expectedHeight = binding.navigationView.height
        repeat(5) {
            binding.root.dispatchApplyWindowInsets(systemInsets.toWindowInsets()!!)
            layout()
            assertEquals("Only the system navigation bar belongs in bottom bar padding",
                initialPadding + systemBottom, binding.navigationView.paddingBottom)
            assertEquals("Bottom bar height must stabilize", expectedHeight, binding.navigationView.height)
            contentBottoms.forEach {
                assertEquals("Every page reserves space for the whole bottom bar", expectedHeight, it)
            }
            assertEquals("Drawer receives original system insets", systemBottom, drawerBottom)
        }
        val behavior = (binding.navigationView.layoutParams as CoordinatorLayout.LayoutParams)
            .behavior as HideViewOnScrollBehavior<View>
        behavior.slideOut(binding.navigationView, false)
        binding.root.dispatchApplyWindowInsets(systemInsets.toWindowInsets()!!)
        layout()
        assertEquals(expectedHeight.toFloat(), binding.navigationView.translationY, 0f)
        assertEquals(expectedHeight, binding.navigationView.height)
        behavior.slideIn(binding.navigationView, false)
        assertEquals(0f, binding.navigationView.translationY, 0f)
        assertEquals(View.VISIBLE, binding.navigationView.visibility)
        if (Build.VERSION.SDK_INT >= 30) {
            assertTrue("Modern Android retains its existing root listener", rootDispatches > 0)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `page consuming insets does not starve the bottom bar or drawer`() {
        val page = FrameLayout(binding.root.context)
        binding.viewPager.addView(page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { _, _ -> WindowInsetsCompat.CONSUMED }
        var drawerBottom = -1
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { _, insets ->
            drawerBottom = insets.systemWindowInsetBottom
            insets
        }
        val initialPadding = binding.navigationView.paddingBottom
        val insets = ReflectionHelpers.callConstructor(WindowInsets::class.java,
            ReflectionHelpers.ClassParameter.from(Rect::class.java, Rect(0, 24, 0, 48)))
        binding.root.dispatchApplyWindowInsets(insets)
        assertEquals(initialPadding + 48, binding.navigationView.paddingBottom)
        assertEquals(48, drawerBottom)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `opening slideshow after home preserves system bar padding`() {
        val context = binding.root.context
        val fragment = SlideshowFragment()
        val root = LayoutInflater.from(context).inflate(R.layout.fragment_slideshow, null)
        val topBar = root.findViewById<View>(R.id.slide_top_bar)
        val bottomBar = root.findViewById<View>(R.id.slide_bottom_bar)
        ReflectionHelpers.setField(fragment, "mView", root)
        ReflectionHelpers.setField(fragment, "topBar", topBar)
        ReflectionHelpers.setField(fragment, "bottomBar", bottomBar)
        ReflectionHelpers.callInstanceMethod<Void>(fragment, "applySystemBarInsets")
        root.setTag(androidx.fragment.R.id.fragment_container_view_tag, fragment)
        val container = FragmentContainerView(context).apply { addView(root) }
        for (bottom in listOf(48, 0, 48)) {
            val insets = ReflectionHelpers.callConstructor(WindowInsets::class.java,
                ReflectionHelpers.ClassParameter.from(Rect::class.java, Rect(0, 24, 0, bottom)))
            container.dispatchApplyWindowInsets(insets)
            assertEquals(24 + 12.ppppx, topBar.paddingTop)
            assertEquals(bottom + 20.ppppx, bottomBar.paddingBottom)
        }
    }
}
