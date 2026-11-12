package ceui.pixiv.ui.artlist

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.witstudio.theme.color
import com.bumptech.glide.Glide
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.UUID

/** Run scripts/dev-artlists.mjs and adb reverse tcp:18087 tcp:18087 first. No production writes. */
@RunWith(AndroidJUnit4::class)
class ArtlistDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private fun api(uid: Long): ArtlistApi = Retrofit.Builder().baseUrl("http://127.0.0.1:18087/")
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            // The server emits production next_url values. All fixture traffic stays on USB.
            val url = req.url.newBuilder().scheme("http").host("127.0.0.1").port(18087).build()
            chain.proceed(req.newBuilder().url(url).header("Authorization", "Bearer device-test-$uid").build())
        }.build()).addConverterFactory(GsonConverterFactory.create()).build().create(ArtlistApi::class.java)

    private fun fragment(f: Fragment): Fragment? = if (f is ArtlistsFragment || f is ArtlistDetailFragment || f is ArtlistEditorFragment) f
        else f.childFragmentManager.fragments.firstNotNullOfOrNull(::fragment)
    private fun children(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
    private fun intent(route: TemplateRoute, id: Long = 0) = Intent(target, TemplateActivity::class.java)
        .putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key).putExtra(ArtlistNavigation.ID, id)
    private fun awaitUi(scenario: ActivityScenario<TemplateActivity>, predicate: (Fragment) -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        do {
            var ready = false
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::fragment)?.let { ready = predicate(it) }
            }
            if (ready) return
            android.os.SystemClock.sleep(60)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        fail("Artlist UI did not reach the expected state")
    }

    @Test fun localBackendFlowsThroughRealFeedsAndEditorSurvivesRecreation() = runBlocking {
        val uid = SessionManager.loggedInUid
        assertTrue("Use the signed-in Pixel 8", uid > 0)
        val local = api(uid)
        ArtlistRepository.apiOverride = local
        val created = mutableListOf<Long>()
        try {
            var title = "夏天、海风和很长很长的画单名称 · Device test"
            val own = ArtlistRepository.create(uid, ArtlistCreate(title, "这些作品想慢慢看。", "测试用户", UUID.randomUUID().toString()))
            created += own.id
            ActivityScenario.launch<TemplateActivity>(intent(TemplateRoute.ARTLISTS).putExtra(ArtlistNavigation.SCOPE, "mine")).use { scenario ->
                awaitUi(scenario) { f -> f is FeedFragment && children(f.requireView()).filterIsInstance<TextView>().any { it.text.toString() == title } }
                // A stopped list misses the edit event, then its Fragment is recreated while
                // the retained feed still contains the old title. It must refresh on return.
                scenario.moveToState(Lifecycle.State.CREATED)
                title = "后台编辑后重建也能更新"
                ArtlistRepository.edit(uid, own.id, ArtlistEdit(name = title))
                scenario.recreate()
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitUi(scenario) { f -> children(f.requireView()).filterIsInstance<TextView>().any { it.text.toString() == title } }
            }
            val work = Gson().fromJson("""{"id":13534647,"type":"illust","title":"测试用风景","visible":true,"restrict":0,"x_restrict":0,"sanity_level":2,"page_count":1,"width":1200,"height":900,"meta_single_page":{"original_image_url":"https://i.pximg.net/test-original.jpg"},"image_urls":{"medium":"https://i.pximg.net/test.jpg"},"user":{"id":88,"name":"画师"}}""", Illust::class.java)
            ArtlistRepository.add(uid, own.id, work)
            ArtlistRepository.add(uid, own.id, work)
            assertEquals(1, ArtlistRepository.detail(uid, own.id).workCount)
            ActivityScenario.launch<TemplateActivity>(intent(TemplateRoute.ARTLIST_DETAIL, own.id)).use { scenario ->
                awaitUi(scenario) { f -> f is ArtlistDetailFragment && f.currentIllustItems().isNotEmpty() }
                scenario.onActivity { activity ->
                    val f = activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::fragment) as ArtlistDetailFragment
                    assertTrue(f.canEdit()); f.setCover(work)
                }
                awaitUi(scenario) { f -> !androidx.lifecycle.ViewModelProvider(f)[ArtlistModel::class.java].busy.value }
                assertEquals(work.id, ArtlistRepository.detail(uid, own.id).cover?.id)
            }
            ActivityScenario.launch<TemplateActivity>(intent(TemplateRoute.ARTLIST_EDIT, own.id)).use { scenario ->
                awaitUi(scenario) { f -> children(f.requireView()).filterIsInstance<EditText>().firstOrNull()?.text?.toString() == title }
                scenario.onActivity { activity ->
                    val f = activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::fragment)!!
                    children(f.requireView()).filterIsInstance<EditText>().first().setText("旋转后仍保留的画单名称")
                }
                scenario.recreate()
                awaitUi(scenario) { f -> children(f.requireView()).filterIsInstance<EditText>().firstOrNull()?.text?.toString() == "旋转后仍保留的画单名称" }
            }
            val otherUid = if (uid == 9000001L) 9000002L else 9000001L
            val otherApi = api(otherUid)
            val other = otherApi.create(otherUid, ArtlistCreate("别人的公开画单", "收藏的是持续更新的画单", "另一位测试用户", UUID.randomUUID().toString())).body()!!
            try {
                assertTrue(ArtlistRepository.save(uid, other.id, true).saved)
                assertTrue(otherApi.add(other.id, otherUid, 0, ArtlistAdd(work)).isSuccessful)
                assertEquals(1, ArtlistRepository.detail(uid, other.id).workCount)
                ActivityScenario.launch<TemplateActivity>(intent(TemplateRoute.ARTLISTS).putExtra(ArtlistNavigation.SCOPE, "saved")).use { scenario ->
                    awaitUi(scenario) { f -> children(f.requireView()).filterIsInstance<TextView>().any { it.text.toString() == other.name } }
                }
                assertFalse(ArtlistRepository.save(uid, other.id, false).saved)
            } finally { otherApi.delete(other.id, otherUid) }
            assertEquals(0, ArtlistRepository.remove(uid, own.id, work.id).workCount)
            ActivityScenario.launch<TemplateActivity>(intent(TemplateRoute.ARTLIST_DETAIL, own.id)).use { scenario ->
                awaitUi(scenario) { f -> children(f.requireView()).filterIsInstance<TextView>().any { it.text.toString() == target.getString(R.string.artlist_works_empty) } }
            }
        } finally {
            created.forEach { local.delete(it, uid) }
            ArtlistRepository.apiOverride = null
        }
    }

    @Test fun filteredFinalPageUpdatesHeaderAndCoversRespectMasking() = runBlocking {
        val uid = SessionManager.loggedInUid
        assertTrue(uid > 0)
        val local = api(uid)
        ArtlistRepository.apiOverride = local
        val oldAi = Shaft.sSettings.isDeleteAIIllust
        val oldStrength = Shaft.sSettings.aiBlockStrength
        val work = Gson().fromJson("""{"id":2000000001,"type":"illust","title":"过滤测试","visible":true,"restrict":0,"x_restrict":0,"illust_ai_type":2,"page_count":1,"meta_single_page":{"original_image_url":"https://i.pximg.net/test-original.jpg"},"image_urls":{"medium":"https://i.pximg.net/test.jpg"}}""", Illust::class.java)
        var listId = 0L
        val wasMuted = IllustMuteStore.isMuted(work.id)
        try {
            Shaft.sSettings.setDeleteAIIllust(true)
            Shaft.sSettings.aiBlockStrength = 1
            assertNull("AI blur must not expose a clear cover", artlistCoverUrl(work))
            Shaft.sSettings.setDeleteAIIllust(false)
            IllustMuteStore.setMuted(work.id, true) { work }
            assertNull(artlistCoverUrl(work))
            IllustMuteStore.setMuted(work.id, false)
            assertEquals(work.image_urls?.medium, artlistCoverUrl(work))

            Shaft.sSettings.setDeleteAIIllust(true)
            Shaft.sSettings.aiBlockStrength = 0
            listId = ArtlistRepository.create(uid, ArtlistCreate("分页过滤", "", "Test", UUID.randomUUID().toString())).id
            for (index in 0..30) ArtlistRepository.add(uid, listId, work.copy(id = work.id + index))
            ActivityScenario.launch<TemplateActivity>(intent(TemplateRoute.ARTLIST_DETAIL, listId)).use { scenario ->
                awaitUi(scenario) { f ->
                    val detail = f as? ArtlistDetailFragment ?: return@awaitUi false
                    val feed = ViewModelProvider(detail).get(FeedViewModel::class.java.name, FeedViewModel::class.java)
                    feed.loadMore()
                    val texts = children(f.requireView()).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
                    feed.uiState.value.reachedEnd &&
                        target.getString(R.string.artlist_filtered_empty) in texts &&
                        target.getString(R.string.artlist_browse) !in texts
                }
            }
        } finally {
            if (listId > 0) local.delete(listId, uid)
            ArtlistRepository.apiOverride = null
            Shaft.sSettings.setDeleteAIIllust(oldAi)
            Shaft.sSettings.aiBlockStrength = oldStrength
            IllustMuteStore.setMuted(work.id, wasMuted) { work }
        }
    }

    @Test fun renderProductionCardsInDayNightNarrowAndLargeText() {
        val photo = instrumentation.context.assets.open("plaza-figma/imgGaleryImg.png").use { BitmapFactory.decodeStream(it) }
        try {
            for (dark in listOf(false, true)) for (large in listOf(false, true)) instrumentation.runOnMainSync {
                val config = Configuration(target.resources.configuration).apply {
                    densityDpi = 160; fontScale = if (large) 1.6f else 1f
                    uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(target.createConfigurationContext(config), R.style.AppTheme)
                val card = ArtlistCardView(context, header = true)
                card.setBackgroundColor(context.color(R.color.v3_bg))
                card.bind(Artlist(1, 1, "想住进画里的夏天：海风、晚霞和回家的路", "把喜欢的风景放在一起。\n这是一段较长的简介，用来检查窄屏和字体放大后的阅读与换行。", "一个很长的创建者名字", 48, 126, false, null))
                card.action("开始浏览", true) {}; card.action("收藏画单") {}
                val image = children(card).filterIsInstance<ImageView>().single()
                Glide.with(image).clear(image); image.setImageBitmap(photo)
                val width = if (large) 320 else 412
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                card.layout(0, 0, width, card.measuredHeight)
                children(card).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.forEach { label ->
                    assertTrue("Text must have usable width", label.width > 0)
                }
                val bitmap = Bitmap.createBitmap(width, card.measuredHeight, Bitmap.Config.ARGB_8888)
                card.draw(Canvas(bitmap))
                File(target.getExternalFilesDir(null), "artlist-${if (dark) "dark" else "light"}-${if (large) "large-text" else "normal"}.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                card.recycle(); bitmap.recycle()
            }
        } finally { photo.recycle() }
    }
}
