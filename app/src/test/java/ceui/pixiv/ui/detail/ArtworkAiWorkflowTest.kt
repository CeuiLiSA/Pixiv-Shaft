package ceui.pixiv.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.upscale.*
import com.blankj.utilcode.util.Utils
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = ArtworkPageReuseTest.TestApplication::class)
class ArtworkAiWorkflowTest {
    private lateinit var host: FragmentActivity
    private lateinit var owner: Fragment
    private val work = Illust(id = 1292L, type = "illust", page_count = 3, width = 600, height = 800)

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        com.hjq.toast.Toaster.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Glide.init(app, GlideBuilder())
        host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        host.setTheme(R.style.AppTheme)
        owner = Fragment(R.layout.view_ai_upscale_overlay)
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, owner).commitNow()
    }

    @After fun tearDown() {
        IllustAiStore.clear(work.id)
        host.finish()
        Glide.tearDown()
    }

    @Test fun `resuming while original is downloading keeps duplicate guard`() {
        val state = IllustAiStore.getOrCreate(work.id, 0)
        state.upscaleStatus = UpscaleStatus.Preparing
        state.rembgStatus = UpscaleStatus.Preparing
        val helper = IllustAiHelper(owner, owner.requireView(), onAiStateChanged = {})
        helper.restoreAiTasksIfRunning(work.id)
        assertEquals(UpscaleStatus.Preparing, state.upscaleStatus)
        assertEquals(UpscaleStatus.Preparing, state.rembgStatus)
    }

    @Test fun `rembg retry after failure observes the replacement task`() {
        val helper = IllustAiHelper(owner, owner.requireView(), onAiStateChanged = {})
        fun observe(task: RembgTask) {
            ReflectionHelpers.callInstanceMethod<Void>(helper, "observeRembgTask",
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, work.id),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
                ReflectionHelpers.ClassParameter.from(RembgTask::class.java, task))
        }
        val first = RembgTask(RembgTask.illustKey(work.id), host, File("unused"), "unused")
        observe(first)
        (first.status as MutableLiveData).value = UpscaleStatus.Failed
        val retry = RembgTask(first.taskKey, host, File("unused"), "unused")
        observe(retry)
        (retry.status as MutableLiveData).value = UpscaleStatus.Running
        assertTrue(retry.status.hasActiveObservers())
        assertEquals(UpscaleStatus.Running, IllustAiStore.get(work.id, 0)?.rembgStatus)
    }

    @Test fun `completed classic result can be opened again without rerunning inference`() {
        val result = File.createTempFile("ai-result", ".png", host.cacheDir)
        try {
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.rembgResultPath = result.absolutePath
            state.rembgOriginalPath = "original"
            IllustAiHelper(owner, owner.requireView()).performRembg(work, RembgModel.U2NETP)
            val intent = shadowOf(host).nextStartedActivity
            assertNotNull(intent)
            assertEquals(result.absolutePath, intent.getStringExtra("rembg_path"))
        } finally {
            result.delete()
        }
    }

    @Test fun `late progress cannot resurrect a completed image overlay`() {
        val adapter = object : NonCollapsibleIllustAdapter(host, owner, work, 600, false) {
            override fun scanLocalDownloads() = Unit
        }
        try {
            val holder = ViewHolder(RecyIllustDetailBinding.inflate(LayoutInflater.from(host)))
            IllustAiStore.getOrCreate(work.id, 0).upscaleStatus = UpscaleStatus.Done
            adapter.updateWorkingOverlayProgress(holder, 0, 1f, 0f, "old progress")
            assertNull(holder.itemView.findViewById<View>(R.id.ai_overlay_root))
        } finally {
            adapter.release()
        }
    }

    @Test fun `queued task progress does not replace the running operation`() {
        val adapter = object : NonCollapsibleIllustAdapter(host, owner, work, 600, false) {
            override fun scanLocalDownloads() = Unit
        }
        try {
            val holder = ViewHolder(RecyIllustDetailBinding.inflate(LayoutInflater.from(host)))
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.rembgStatus = UpscaleStatus.Running
            state.rembgStatusText = "removing"
            state.rembgProgress = .5f
            state.upscaleStatus = UpscaleStatus.Queued
            adapter.updateWorkingOverlayProgress(holder, 0, 0f, 0f, "queued")
            assertEquals("removing", holder.itemView.findViewById<TextView>(R.id.ai_status_text).text)
            assertEquals("50%", holder.itemView.findViewById<TextView>(R.id.ai_progress_text).text)
        } finally {
            adapter.release()
        }
    }

    @Test fun `tablet overlays are measured and laid out inside the fitted image`() {
        val frame = FrameLayout(host)
        val stage = ArtworkTabletStage(ArtworkV3Fragment(), frame, FrameLayout(host),
            ImageView(host), View(host), { false }, {}, {}, {})
        val clazz = Class.forName("ceui.pixiv.ui.detail.ArtworkTabletStage\$PageAdapter")
        val constructor = clazz.declaredConstructors.single().apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val adapter = constructor.newInstance(stage, work, 3) as RecyclerView.Adapter<RecyclerView.ViewHolder>
        val holder = adapter.createViewHolder(frame, 0)
        val result = File.createTempFile("ai-result", ".png", host.cacheDir)
        try {
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.rembgStatus = UpscaleStatus.Running
            state.upscaleResultPath = result.absolutePath
            clazz.getDeclaredMethod("bindAiOverlayAndPills", holder.javaClass, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(adapter, holder, 0)
            holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            holder.itemView.layout(0, 0, 800, 600)
            val overlay = holder.itemView.findViewById<View>(R.id.ai_overlay_root)
            val pill = holder.itemView.findViewById<View>(R.id.ai_upscale_pill)
            assertTrue(overlay.width > 0 && overlay.height > 0)
            assertTrue(pill.width > 0 && pill.height > 0)
            assertEquals(overlay.width, (overlay.parent as View).width)
        } finally {
            result.delete()
            stage.release()
        }
    }

    @Test fun `preparing another operation does not hide running progress`() {
        val state = PageAiState(work.id, 0, upscaleStatus = UpscaleStatus.Preparing,
            rembgStatus = UpscaleStatus.Running, rembgProgress = .5f, rembgStatusText = "removing")
        assertTrue(state.upscaleRunning)
        assertFalse(state.isIndeterminate)
        assertEquals(.5f, state.currentProgress)
        assertEquals("removing", state.currentStatusText(host))
    }

    @Test fun `two page snapshot manga still opens the snapshot viewer`() {
        val manga = work.copy(type = "manga", page_count = 2)
        val adapter = object : NonCollapsibleIllustAdapter(host, owner, manga, 600, false) {
            override fun scanLocalDownloads() = Unit
        }
        try {
            adapter.setSnapshotId("test-snapshot")
            val holder = ViewHolder(RecyIllustDetailBinding.inflate(LayoutInflater.from(host)))
            adapter.onBindViewHolder(holder, 0)
            holder.itemView.performClick()
            val intent = shadowOf(host).nextStartedActivity
            assertNotNull(intent)
            assertEquals("快照大图", intent.getStringExtra("dataType"))
        } finally {
            adapter.release()
        }
    }

    @Test fun `gallery restores a queued task with visible waiting feedback`() {
        val key = "gallery_test"
        val task = UpscaleTask(key, host, File("unused"), "unused")
        (task.status as MutableLiveData).value = UpscaleStatus.Queued
        val tasks = ReflectionHelpers.getField<MutableMap<String, UpscaleTask>>(UpscaleTaskPool, "_tasks")
        tasks[key] = task
        try {
            val gallery = FragmentAiUpscale()
            ReflectionHelpers.setField(gallery, "currentTaskKey", key)
            host.supportFragmentManager.beginTransaction().replace(android.R.id.content, gallery).commitNow()
            val root = gallery.requireView()
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.ai_overlay_root).visibility)
            assertEquals(host.getString(R.string.string_ai_task_queued),
                root.findViewById<TextView>(R.id.ai_status_text).text)
        } finally {
            UpscaleTaskPool.removeTask(key)
        }
    }
}
