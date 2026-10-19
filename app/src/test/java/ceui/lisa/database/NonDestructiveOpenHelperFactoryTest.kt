package ceui.lisa.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1261：SQLite 报 corrupt 时不许把整个库删掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NonDestructiveOpenHelperFactoryTest {

    private val context = RuntimeEnvironment.getApplication()

    private val schema = object : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE t (v TEXT)")
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** 记下包装后的 callback，好直接驱动 onCorruption。 */
    private var wrapped: SupportSQLiteOpenHelper.Callback? = null

    private fun open(name: String): SupportSQLiteOpenHelper =
        NonDestructiveOpenHelperFactory { config ->
            wrapped = config.callback
            FrameworkSQLiteOpenHelperFactory().create(config)
        }.create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(schema).build())

    @Test fun `库打开着时报 corrupt 不关不删`() {
        val helper = open("open.db")
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO t VALUES ('keep')")

        wrapped!!.onCorruption(db)

        assertTrue(db.isOpen)
        assertTrue(context.getDatabasePath("open.db").exists())
        db.query("SELECT v FROM t").use {
            assertTrue(it.moveToFirst())
            assertEquals("keep", it.getString(0))
        }
        helper.close()
    }

    @Test fun `库打不开时挪到 corrupt 备份而不是删掉，并能建出新库`() {
        val file = context.getDatabasePath("broken.db")
        file.parentFile!!.mkdirs()
        val garbage = ByteArray(8192) { (it * 31 + 7).toByte() }
        file.writeBytes(garbage)

        val helper = open("broken.db")
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO t VALUES ('fresh')")

        val quarantined = File(file.path + NonDestructiveOpenHelperFactory.QUARANTINE_SUFFIX)
        assertTrue(quarantined.exists())
        assertArrayEquals(garbage, quarantined.readBytes())
        helper.close()
    }
}
