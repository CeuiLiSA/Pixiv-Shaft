package ceui.lisa.fragments

import android.app.Application
import android.content.Context
import android.os.CancellationSignal
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitTipDialog
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsDataManageDialogTest {
    private lateinit var controller: ActivityController<StatsHostActivity>
    private lateinit var fragment: FragmentSettingsData
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        AppDatabase.destroyInstance()
        app.deleteDatabase(AppDatabase.DATABASE_NAME)
        db = AppDatabase.getAppDatabase(app)
        // Finish schema creation before the background request starts.
        db.openHelper.writableDatabase
        controller = Robolectric.buildActivity(StatsHostActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.create()
        fragment = FragmentSettingsData()
        controller.get().supportFragmentManager.beginTransaction().add(fragment, "settings").commitNow()
    }

    @After fun tearDown() {
        controller.get().statsWorker?.join(5_000)
        ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
        AppDatabase.destroyInstance()
        RuntimeEnvironment.getApplication().deleteDatabase(AppDatabase.DATABASE_NAME)
    }

    @Test fun `cancel wins over an already queued statistics result`() {
        val loading = requestAndAwaitWorker()
        val signal = ReflectionHelpers.getField<CancellationSignal>(fragment, "manageDialogSignal")
        loading.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(signal.isCanceled)
        assertFalse("Cancelled loading must not reopen the management dialog",
            ShadowDialog.getShownDialogs().any { it.isShowing })
    }

    @Test fun `dismiss wins over an already queued statistics result`() {
        val loading = requestAndAwaitWorker()
        loading.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("Dismissed loading must not reopen the management dialog",
            ShadowDialog.getShownDialogs().any { it.isShowing })
    }

    @Test fun `destroyed page does not deliver an already queued statistics result`() {
        requestAndAwaitWorker()
        controller.get().supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(ShadowDialog.getShownDialogs().any { it.isShowing })
    }

    @Test fun `successful result closes loading and opens management without cancellation`() {
        val loading = requestAndAwaitWorker()
        val signal = ReflectionHelpers.getField<CancellationSignal>(fragment, "manageDialogSignal")
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(loading.isShowing)
        assertFalse(signal.isCanceled)
        assertTrue(ShadowDialog.getShownDialogs().any { it is WitDialog && it.isShowing })
    }

    private fun requestAndAwaitWorker(): WitTipDialog {
        ReflectionHelpers.callInstanceMethod<Void>(fragment, "showManageBulkDownloadDialog")
        val loading = ReflectionHelpers.getField<WitTipDialog>(fragment, "manageLoadingDialog")
        assertTrue(loading.isShowing)
        val host = controller.get()
        assertTrue("Statistics worker must start", host.statsStarted.await(5, TimeUnit.SECONDS))
        val worker = requireNotNull(host.statsWorker)
        worker.join(5_000)
        assertFalse("Statistics worker must finish before delivery", worker.isAlive)
        return loading
    }
}

/** Observe the worker so tests can control delivery without timing sleeps. */
class StatsHostActivity : FragmentActivity() {
    val statsStarted = CountDownLatch(1)
    @Volatile var statsWorker: Thread? = null

    override fun getApplicationContext(): Context {
        val current = Thread.currentThread()
        if (current.name == "bulk-dl-stats") {
            statsWorker = current
            statsStarted.countDown()
        }
        return super.getApplicationContext()
    }
}
