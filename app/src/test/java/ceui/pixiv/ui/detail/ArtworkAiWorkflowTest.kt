package ceui.pixiv.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import ceui.lisa.activities.ImageDetailActivity
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
    private lateinit var hostController: org.robolectric.android.controller.ActivityController<FragmentActivity>
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
        hostController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        host = hostController.get()
        host.setTheme(R.style.AppTheme)
        owner = Fragment(R.layout.view_ai_upscale_overlay)
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, owner).commitNow()
    }

    @After fun tearDown() {
        RembgTaskPool.findTasksForIllust(work.id).forEach { RembgTaskPool.removeTask(it.second.taskKey) }
        UpscaleTaskPool.findTasksForIllust(work.id).forEach { UpscaleTaskPool.removeTask(it.second.taskKey) }
        hostController.pause().stop().destroy()
        IllustAiStore.clear(work.id)
        // 消费单例 LiveData 的 pending post，避免下一用例重置 Looper 后留下未派发标记。
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Glide.tearDown()
    }

    @Test fun `cleanup does not restore a deleted upscale from the completed task pool`() {
        assertCleanupDoesNotRestoreCompletedTask(clearUpscale = true)
    }

    @Test fun `cleanup does not restore deleted background removal from the completed task pool`() {
        assertCleanupDoesNotRestoreCompletedTask(clearUpscale = false)
    }

    private fun assertCleanupDoesNotRestoreCompletedTask(clearUpscale: Boolean) {
        val upscaleFile = File(host.filesDir, "upscale-cache/cleanup_${work.id}.png").apply {
            parentFile!!.mkdirs()
            writeText("upscale")
        }
        val rembgFile = File(host.filesDir, "rembg-cache/cleanup_${work.id}.png").apply {
            parentFile!!.mkdirs()
            writeText("rembg")
        }
        try {
            val upscale = UpscaleTask(UpscaleTask.illustKey(work.id), host, File("unused"), "unused")
            (upscale.resultFile as MutableLiveData).value = upscaleFile
            (upscale.status as MutableLiveData).value = UpscaleStatus.Done
            ReflectionHelpers.getField<MutableMap<String, UpscaleTask>>(UpscaleTaskPool, "_tasks")[upscale.taskKey] = upscale
            val rembg = RembgTask(RembgTask.illustKey(work.id), host, File("unused"), "unused")
            (rembg.resultFile as MutableLiveData).value = rembgFile
            (rembg.status as MutableLiveData).value = UpscaleStatus.Done
            ReflectionHelpers.getField<MutableMap<String, RembgTask>>(RembgTaskPool, "_tasks")[rembg.taskKey] = rembg
            val helper = IllustAiHelper(owner, owner.requireView(), onAiStateChanged = {})
            helper.restoreAiTasksIfRunning(work.id)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.acknowledgeResult()

            assertTrue(AiResultStorage.clear(host, clearUpscale, !clearUpscale, false))
            IllustAiStore.pruneMissingResults()
            // Real detail pages restore completed task state on this LiveData notification.
            shadowOf(android.os.Looper.getMainLooper()).idle()

            if (clearUpscale) {
                assertNull(state.upscaleResultPath)
                assertEquals(UpscaleStatus.Idle, state.upscaleStatus)
                assertNull(UpscaleTaskPool.getTask(upscale.taskKey))
                assertSame(rembg, RembgTaskPool.getTask(rembg.taskKey))
            } else {
                assertNull(state.rembgResultPath)
                assertEquals(UpscaleStatus.Idle, state.rembgStatus)
                assertNull(RembgTaskPool.getTask(rembg.taskKey))
                assertSame(upscale, UpscaleTaskPool.getTask(upscale.taskKey))
            }
            assertFalse(state.hasUnacknowledgedResult)
        } finally {
            upscaleFile.delete()
            rembgFile.delete()
        }
    }

    @Test fun `resuming while original is downloading keeps duplicate guard`() {
        val old = UpscaleTask(UpscaleTask.illustKey(work.id), host, File("unused"), "unused")
        (old.status as MutableLiveData).value = UpscaleStatus.Done
        ReflectionHelpers.getField<MutableMap<String, UpscaleTask>>(UpscaleTaskPool, "_tasks")[old.taskKey] = old
        val state = IllustAiStore.getOrCreate(work.id, 0)
        state.upscaleStatus = UpscaleStatus.Preparing
        state.rembgStatus = UpscaleStatus.Preparing
        val helper = IllustAiHelper(owner, owner.requireView(), onAiStateChanged = {})
        helper.restoreAiTasksIfRunning(work.id)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(UpscaleStatus.Preparing, state.upscaleStatus)
        assertEquals(UpscaleStatus.Preparing, state.rembgStatus)
    }

    @Test fun `rembg retry after failure observes the replacement task`() {
        val helper = IllustAiHelper(owner, owner.requireView(), onAiStateChanged = {})
        fun observe(task: RembgTask) {
            ReflectionHelpers.getField<MutableMap<String, RembgTask>>(RembgTaskPool, "_tasks")[task.taskKey] = task
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

    @Test fun `stopped detail cannot erase a replacement rembg task on resume`() {
        val tasks = ReflectionHelpers.getField<MutableMap<String, RembgTask>>(RembgTaskPool, "_tasks")
        val key = RembgTask.illustKey(work.id)
        val first = RembgTask(key, host, File("unused"), "unused")
        tasks[key] = first
        val helper = IllustAiHelper(owner, owner.requireView(), onAiStateChanged = {})
        try {
            (first.status as MutableLiveData).value = UpscaleStatus.Running
            helper.restoreAiTasksIfRunning(work.id)
            hostController.pause().stop()
            (first.status as MutableLiveData).value = UpscaleStatus.Failed
            val retry = RembgTask(key, host, File("unused"), "unused")
            (retry.status as MutableLiveData).value = UpscaleStatus.Running
            tasks[key] = retry
            IllustAiStore.getOrCreate(work.id, 0).rembgStatus = UpscaleStatus.Running
            hostController.start().resume()
            assertSame(retry, RembgTaskPool.getTask(key))
            assertEquals(UpscaleStatus.Running, IllustAiStore.get(work.id, 0)?.rembgStatus)
            helper.restoreAiTasksIfRunning(work.id)
            assertTrue(retry.status.hasActiveObservers())
        } finally {
            RembgTaskPool.removeTask(key)
        }
    }

    @Test fun `stopped detail cannot overwrite replacement upscale state`() {
        val tasks = ReflectionHelpers.getField<MutableMap<String, UpscaleTask>>(UpscaleTaskPool, "_tasks")
        val key = UpscaleTask.illustKey(work.id)
        val first = UpscaleTask(key, host, File("unused"), "unused")
        tasks[key] = first
        val helper = IllustAiHelper(owner, owner.requireView(), onAiStateChanged = {})
        (first.status as MutableLiveData).value = UpscaleStatus.Running
        helper.restoreAiTasksIfRunning(work.id)
        hostController.pause().stop()
        (first.status as MutableLiveData).value = UpscaleStatus.Failed
        val retry = UpscaleTask(key, host, File("unused"), "unused")
        (retry.status as MutableLiveData).value = UpscaleStatus.Running
        tasks[key] = retry
        IllustAiStore.getOrCreate(work.id, 0).upscaleStatus = UpscaleStatus.Running
        hostController.start().resume()
        assertSame(retry, UpscaleTaskPool.getTask(key))
        assertEquals(UpscaleStatus.Running, IllustAiStore.get(work.id, 0)?.upscaleStatus)
        helper.restoreAiTasksIfRunning(work.id)
        assertTrue(retry.status.hasActiveObservers())
    }

    @Test fun `restoring a pending classic result does not accumulate observers`() {
        val task = UpscaleTask(UpscaleTask.illustKey(work.id), host, File("unused"), "unused")
        ReflectionHelpers.getField<MutableMap<String, UpscaleTask>>(UpscaleTaskPool, "_tasks")[task.taskKey] = task
        val helper = IllustAiHelper(owner, owner.requireView())
        (task.status as MutableLiveData).value = UpscaleStatus.Running
        helper.restoreAiTasksIfRunning(work.id)
        hostController.pause()
        (task.status as MutableLiveData).value = UpscaleStatus.Done
        helper.restoreAiTasksIfRunning(work.id)
        helper.restoreAiTasksIfRunning(work.id)
        assertEquals(1, ReflectionHelpers.getField<Int>(task.status, "mActiveCount"))
        hostController.resume()
    }

    @Test fun `initial null translation status does not hide an existing AI result`() {
        val controller = Robolectric.buildActivity(ImageDetailActivity::class.java).create()
        val viewer = controller.get()
        val result = File.createTempFile("ai-result", ".png", host.cacheDir)
        try {
            ReflectionHelpers.setField(viewer, "mIllust", work)
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.upscaleResultPath = result.absolutePath
            state.upscaleOriginalPath = "original"
            ReflectionHelpers.callInstanceMethod<Void>(viewer, "syncAiOverlayForPage",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0))
            controller.start().resume().visible()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            val overlay = viewer.findViewById<View>(R.id.ai_overlay_root)
            assertEquals(View.VISIBLE, overlay.visibility)
            assertEquals(1f, overlay.alpha)
        } finally {
            controller.pause().stop().destroy()
            result.delete()
        }
    }

    @Test fun `viewer opened during preparation observes the task when it enters the queue`() {
        val blocker = object : AiQueueItem {
            override val taskKey = "test-blocker"
            override val status = MutableLiveData(UpscaleStatus.Running)
            override fun markQueued() = Unit
            override fun execute() = Unit
        }
        AiTaskQueue.enqueue(blocker)
        val controller = Robolectric.buildActivity(ImageDetailActivity::class.java).setup()
        val viewer = controller.get()
        val key = RembgTask.illustKey(work.id)
        try {
            ReflectionHelpers.setField(viewer, "mIllust", work)
            IllustAiStore.getOrCreate(work.id, 0).rembgStatus = UpscaleStatus.Preparing
            ReflectionHelpers.callInstanceMethod<Void>(viewer, "syncAiOverlayForPage",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0))
            val task = RembgTaskPool.startTask(key, host, File("unused"), "unused")
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(UpscaleStatus.Queued, task.status.value)
            assertTrue(task.status.hasActiveObservers())
            assertEquals(host.getString(R.string.string_ai_task_queued),
                viewer.findViewById<TextView>(R.id.ai_status_text).text)
        } finally {
            RembgTaskPool.removeTask(key)
            AiTaskQueue.onTaskFinished(blocker)
            controller.pause().stop().destroy()
        }
    }

    @Test fun `preparation failure in another page clears the viewer waiting overlay`() {
        val controller = Robolectric.buildActivity(ImageDetailActivity::class.java).setup()
        val viewer = controller.get()
        try {
            ReflectionHelpers.setField(viewer, "mIllust", work)
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.rembgStatus = UpscaleStatus.Preparing
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE, viewer.findViewById<View>(R.id.ai_overlay_root).visibility)
            state.rembgStatus = UpscaleStatus.Idle
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertEquals(View.GONE, viewer.findViewById<View>(R.id.ai_overlay_root).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `translation takes over a done overlay and restores it on completion`() {
        val controller = Robolectric.buildActivity(ImageDetailActivity::class.java).setup()
        val viewer = controller.get()
        val result = File.createTempFile("ai-result", ".png", host.cacheDir)
        try {
            ReflectionHelpers.setField(viewer, "mIllust", work)
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.upscaleResultPath = result.absolutePath
            state.upscaleOriginalPath = "original"
            ReflectionHelpers.callInstanceMethod<Void>(viewer, "syncAiOverlayForPage",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0))
            val vm = androidx.lifecycle.ViewModelProvider(viewer)[ceui.lisa.activities.ImageTranslationViewModel::class.java]
            val running = ReflectionHelpers.getField<MutableLiveData<Boolean>>(vm, "_running")
            val status = ReflectionHelpers.getField<MutableLiveData<ceui.pixiv.ui.translate.MangaPageTranslatePipeline.Stage?>>(vm, "_status")
            running.value = true
            status.value = ceui.pixiv.ui.translate.MangaPageTranslatePipeline.Stage("translating", 50)
            assertEquals(View.VISIBLE, viewer.findViewById<View>(R.id.ai_loading_state).visibility)
            assertEquals(View.GONE, viewer.findViewById<View>(R.id.ai_done_state).visibility)
            status.value = null
            running.value = false
            assertEquals(View.GONE, viewer.findViewById<View>(R.id.ai_loading_state).visibility)
            assertEquals(View.VISIBLE, viewer.findViewById<View>(R.id.ai_done_state).visibility)
        } finally {
            controller.pause().stop().destroy()
            result.delete()
        }
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

    private fun finishedUpscale(result: File): UpscaleTask {
        val task = UpscaleTask(UpscaleTask.illustKey(work.id), host, File("unused"), "original")
        (task.resultFile as MutableLiveData).value = result
        (task.status as MutableLiveData).value = UpscaleStatus.Done
        ReflectionHelpers.getField<MutableMap<String, UpscaleTask>>(UpscaleTaskPool, "_tasks")[task.taskKey] = task
        IllustAiStore.getOrCreate(work.id, 0).apply {
            upscaleStatus = UpscaleStatus.Done
            upscaleResultPath = result.absolutePath
            upscaleOriginalPath = "original"
        }
        return task
    }

    @Test fun `result seen in the viewer does not prompt again on the classic page`() {
        val result = File.createTempFile("ai-result", ".png", host.cacheDir)
        try {
            val task = finishedUpscale(result)
            IllustAiStore.getOrCreate(work.id, 0).acknowledgeResult()
            IllustAiHelper(owner, owner.requireView()).restoreAiTasksIfRunning(work.id)
            assertNull(shadowOf(host).nextStartedActivity)
            assertEquals(View.GONE, owner.requireView().findViewById<View>(R.id.ai_overlay_root).visibility)
            assertNull(UpscaleTaskPool.getTask(task.taskKey))
        } finally {
            result.delete()
        }
    }

    @Test fun `unseen result still opens from the classic page`() {
        val result = File.createTempFile("ai-result", ".png", host.cacheDir)
        try {
            finishedUpscale(result)
            IllustAiHelper(owner, owner.requireView()).restoreAiTasksIfRunning(work.id)
            assertEquals(result.absolutePath, shadowOf(host).nextStartedActivity?.getStringExtra("upscaled_path"))
            assertFalse(IllustAiStore.getOrCreate(work.id, 0).hasUnacknowledgedResult)
        } finally {
            result.delete()
        }
    }

    @Test fun `opening a result from its pill acknowledges it`() {
        val result = File.createTempFile("ai-result", ".png", host.cacheDir)
        try {
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.rembgResultPath = result.absolutePath
            state.rembgOriginalPath = "original"
            IllustAiHelper(owner, owner.requireView()).openRembgHighlight(state)
            assertEquals(result.absolutePath, shadowOf(host).nextStartedActivity?.getStringExtra("rembg_path"))
            assertFalse(state.hasUnacknowledgedResult)
        } finally {
            result.delete()
        }
    }

    @Test fun `only a collapsed first page hides the result pills`() {
        val collapsed = CollapsibleIllustAdapter(host, owner, work, 600, false)
        val expanded = CollapsibleIllustAdapter(host, owner, work, 600, false, initiallyExpanded = true)
        val flat = NonCollapsibleIllustAdapter(host, owner, work.copy(page_count = 2), 600, false)
        try {
            assertTrue(collapsed.showsExpandPill(0))
            assertFalse(collapsed.showsExpandPill(1))
            assertFalse(expanded.showsExpandPill(0))
            assertFalse(flat.showsExpandPill(0))
        } finally {
            collapsed.release()
            expanded.release()
            flat.release()
        }
    }

    @Test fun `viewer done overlay names the finished operation`() {
        val controller = Robolectric.buildActivity(ImageDetailActivity::class.java).setup()
        val viewer = controller.get()
        val result = File.createTempFile("ai-result", ".png", host.cacheDir)
        try {
            ReflectionHelpers.setField(viewer, "mIllust", work)
            val state = IllustAiStore.getOrCreate(work.id, 0)
            state.rembgResultPath = result.absolutePath
            state.rembgOriginalPath = "original"
            ReflectionHelpers.callInstanceMethod<Void>(viewer, "syncAiOverlayForPage",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0))
            assertEquals(host.getString(R.string.string_ai_rembg_done),
                viewer.findViewById<TextView>(R.id.ai_done_title).text)
        } finally {
            controller.pause().stop().destroy()
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
