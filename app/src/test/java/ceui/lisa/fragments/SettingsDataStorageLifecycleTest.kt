package ceui.lisa.fragments

import android.app.Application
import android.content.Context
import android.os.CancellationSignal
import android.text.format.Formatter
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [BlockingSizeFormatter::class])
class SettingsDataStorageLifecycleTest {
    @Test fun `leaving settings during size formatting does not crash the worker`() {
        val app = RuntimeEnvironment.getApplication()
        AppDatabase.destroyInstance()
        app.deleteDatabase(AppDatabase.DATABASE_NAME)
        val db = AppDatabase.getAppDatabase(app)
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.create()
        BlockingSizeFormatter.reset()
        try {
            db.downloadDao().insertIgnoreAll(listOf(DownloadEntity().apply {
                fileName = "1.png"
                illustGson = "record"
                illustId = 1L
            }))
            val activity = controller.get()
            val fragment = FragmentSettingsData()
            activity.supportFragmentManager.beginTransaction().add(fragment, "settings").commitNow()
            ReflectionHelpers.callInstanceMethod<Void>(fragment, "loadBulkDownloadCacheSizeAsync",
                ReflectionHelpers.ClassParameter.from(TextView::class.java, TextView(activity)))
            assertTrue("Worker must reach formatting", BlockingSizeFormatter.started.await(5, TimeUnit.SECONDS))
            val signal = ReflectionHelpers.getField<CancellationSignal>(fragment, "bulkSizeSignal")

            activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            assertTrue(signal.isCanceled)
            BlockingSizeFormatter.resume.countDown()
            val worker = BlockingSizeFormatter.worker.get()
            worker.join(5_000)
            assertFalse("Worker must finish after detachment", worker.isAlive)
            assertNull("Detached fragment must not crash formatting", BlockingSizeFormatter.failure.get())
        } finally {
            BlockingSizeFormatter.resume.countDown()
            BlockingSizeFormatter.worker.get()?.join(5_000)
            controller.destroy()
            db.close()
            AppDatabase.destroyInstance()
            app.deleteDatabase(AppDatabase.DATABASE_NAME)
        }
    }
}

/** Pause exactly after the worker's cancellation check, before it formats the approximation. */
@Implements(Formatter::class)
class BlockingSizeFormatter {
    companion object {
        lateinit var started: CountDownLatch
        lateinit var resume: CountDownLatch
        val worker = AtomicReference<Thread>()
        val failure = AtomicReference<Throwable>()

        fun reset() {
            started = CountDownLatch(1)
            resume = CountDownLatch(1)
            worker.set(null)
            failure.set(null)
        }

        @JvmStatic
        @Implementation
        fun formatShortFileSize(context: Context, bytes: Long): String {
            val current = Thread.currentThread()
            worker.set(current)
            current.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
            started.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            return "$bytes B"
        }
    }
}
