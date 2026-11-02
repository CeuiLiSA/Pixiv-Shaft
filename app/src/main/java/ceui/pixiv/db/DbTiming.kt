package ceui.pixiv.db

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.database.Cursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 数据库调用的「真实耗时」监测。
 *
 * ## 为什么不用定时探针
 *
 * 之前试过每 2 秒跑一条 `SELECT COUNT(*)` 的看门狗，三个问题：
 * 探针和真实负载抢同一个连接池（池子被占死时它自己也在排队）；测的是合成查询，
 * 卡住了也说不出**是哪条 SQL**；空闲也要每 2 秒醒一次。
 *
 * 这里改成量**真实调用**：所有库访问都从 [DbTimingHelperFactory] 这一个咽喉过，
 * 在 `query` / `compileStatement` / `execSQL` 上记开始时间与 SQL 原文。
 * **没有调用在飞时，一条定时器都不挂**；有调用在飞时只挂一个延时检查，
 * 到点扫一遍「谁超过 [SLOW_MS] 还没回来」。
 *
 * ## 判据
 *
 * 单次调用超过 [SLOW_MS]（默认 2 秒）即判「长排队 / 卡死」。正常查询是毫秒级，
 * 2 秒足够排除调度抖动；比阈值更短的真卡顿对用户也还不构成「界面死了」。
 *
 * ## 误报
 *
 * 清理大表、跑迁移这类「我们自己知道要慢」的操作，用 [beginLongOperation] /
 * [endLongOperation] 包一下，期间不判、不弹。
 */
object DbCallTracker {

    private const val TAG = "DbCallTracker"

    /** 单次调用超过这个时长即判定长排队/卡死。 */
    private const val SLOW_MS = 2_000L

    private class Call(val sql: String, val startMs: Long, val thread: String, @Volatile var firstRowMs: Long = -1L)

    private val handler = Handler(Looper.getMainLooper())
    private val seq = AtomicLong(0)
    private val active = ConcurrentHashMap<Long, Call>()
    private val checkArmed = AtomicBoolean(false)

    /** 我们自己知道要慢的操作计数（清理 / 迁移），大于 0 时不判、不弹。 */
    private val longOps = AtomicInteger(0)

    @Volatile private var stalled = false
    @Volatile private var stalledSql: String = ""
    @Volatile private var stalledSince = 0L
    @Volatile private var lastEpisodeEndAt = 0L

    private var dialog: DbStallDialog? = null

    /** 防重放：一次卡顿只弹一个窗，结束后 [COOLDOWN_MS] 内不再弹。 */
    private const val COOLDOWN_MS = 30_000L

    // ── 对外 ────────────────────────────────────────────────────────────

    /** 进程启动时调一次，只用来跟踪前台页面（弹窗宿主 + 换页跟随）。 */
    @JvmStatic
    fun install() {
        val app = Shaft.getContext() as Application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // 卡着的时候换页：把弹窗跟到新页面，别让用户以为它自己好了。
                if (stalled && dialog == null) showDialogIfPossible(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                // 弹窗挂在被销毁的页面上会 WindowLeaked。只解绑，不结束这次判定。
                dialog?.let {
                    if (it.host === activity) {
                        it.dismissQuietly()
                        dialog = null
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /** 标记一段「我们主动发起的、合法的慢操作」。 */
    @JvmStatic
    fun beginLongOperation() {
        longOps.incrementAndGet()
    }

    @JvmStatic
    fun endLongOperation() {
        longOps.decrementAndGet()
    }

    // ── 由装饰器调用 ────────────────────────────────────────────────────

    fun begin(sql: String): Long {
        val token = seq.incrementAndGet()
        active[token] = Call(sql, SystemClock.elapsedRealtime(), Thread.currentThread().name)
        armCheck()
        return token
    }

    fun markFirstRow(token: Long) {
        val call = active[token] ?: return
        if (call.firstRowMs < 0) {
            call.firstRowMs = SystemClock.elapsedRealtime() - call.startMs
        }
    }

    fun end(token: Long) {
        val call = active.remove(token) ?: return
        val cost = SystemClock.elapsedRealtime() - call.startMs
        val detail = if (call.firstRowMs >= 0) " (首行 ${call.firstRowMs} ms)" else ""
        if (cost >= SLOW_MS) {
            Timber.tag(TAG).w("慢调用 %d ms%s: %s", cost, detail, call.sql.take(200))
        }
        // 试验性开关：把每一条 SQL 的耗时都打出来，而不只是超阈值的。
        // 默认关；开着时日志量很大，只适合短时间排查。读 MMKV 是 mmap + 解码，
        // 相对一次查询的开销可以忽略，所以不额外做缓存（少一层失效风险）。
        if (DbTracePrefs.isEnabled) {
            Timber.tag(TAG).d("DB %5d ms%s  %s", cost, detail, call.sql.take(300).replace('\n', ' '))
        }
        if (active.isEmpty()) {
            handler.removeCallbacks(checkRunnable)
            checkArmed.set(false)
            if (stalled) recover()
        }
    }

    private fun armCheck() {
        if (longOps.get() > 0) return
        if (checkArmed.compareAndSet(false, true)) {
            handler.postDelayed(checkRunnable, SLOW_MS)
        }
    }

    private val checkRunnable = Runnable {
        checkArmed.set(false)
        val now = SystemClock.elapsedRealtime()
        val slow = active.entries.filter { now - it.value.startMs >= SLOW_MS }
        if (slow.isNotEmpty()) {
            if (longOps.get() == 0 && !stalled) {
                val worst = slow.maxByOrNull { now - it.value.startMs }!!.value
                stalled = true
                stalledSql = worst.sql
                stalledSince = worst.startMs
                Timber.tag(TAG).w("判定卡顿: %d ms, sql=%s", now - worst.startMs, worst.sql.take(200))
                showDialogIfPossible(Shaft.getCurrentActivity())
            }
            armCheck()   // 继续盯着
        } else if (active.isNotEmpty()) {
            armCheck()
        } else if (stalled) {
            recover()
        }
    }

    private fun recover() {
        val lasted = SystemClock.elapsedRealtime() - stalledSince
        stalled = false
        stalledSql = ""
        stalledSince = 0L
        lastEpisodeEndAt = SystemClock.elapsedRealtime()
        Timber.tag(TAG).i("库已恢复，本次卡顿 %d ms", lasted)
        dialog?.dismissQuietly()
        dialog = null
    }

    private fun showDialogIfPossible(activity: Activity?) {
        if (!stalled || dialog != null) return
        // 冷却期内不再弹：连续抖动的库不该把用户反复糊一脸。
        val now = SystemClock.elapsedRealtime()
        if (lastEpisodeEndAt != 0L && now - lastEpisodeEndAt < COOLDOWN_MS) return
        if (activity == null || activity.isFinishing || activity.isDestroyed) return

        dialog = DbStallDialog.show(activity) { buildDiagnostics() }
        Timber.tag(TAG).i("卡顿提示已弹出 on %s", activity.javaClass.simpleName)
    }

    /** 「复制日志」的内容。 */
    private fun buildDiagnostics(): String {
        val sb = StringBuilder()
        val now = SystemClock.elapsedRealtime()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        sb.append("数据库卡顿现场 ").append(fmt.format(java.util.Date())).append('\n')
        sb.append("持续: ").append(if (!stalled) "已恢复" else "${now - stalledSince} ms").append('\n')
        sb.append("阈值: ").append(SLOW_MS).append(" ms   在飞调用: ").append(active.size).append('\n')
        if (stalledSql.isNotEmpty()) {
            sb.append("\n--- 卡住的 SQL ---\n").append(stalledSql.take(600)).append('\n')
        }
        val others = active.entries.filter { it.value.sql != stalledSql }
        if (others.isNotEmpty()) {
            sb.append("\n--- 其它在飞调用 ---\n")
            others.take(6).forEach {
                sb.append("- [").append(now - it.value.startMs).append(" ms] ")
                    .append(it.value.thread).append(": ")
                    .append(it.value.sql.take(200).replace('\n', ' ')).append('\n')
            }
        }
        runCatching {
            val f = java.io.File(Shaft.getContext().getDatabasePath(AppDatabase.DATABASE_NAME).path)
            sb.append("\n库文件: ").append(f.length() / 1048576).append(" MB")
            val wal = java.io.File(f.path + "-wal")
            if (wal.exists()) sb.append("  wal: ").append(wal.length() / 1024).append(" KB")
            sb.append('\n')
        }
        return sb.toString()
    }
}

/**
 * 把 [SupportSQLiteOpenHelper.Factory] 包一层，让拿到的库是 [TimingDatabase]。
 * 放在 [AppDatabase] 的 `openHelperFactory` 上，全库访问就都过这里。
 */
class DbTimingHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        return TimingHelper(delegate.create(configuration))
    }

    private class TimingHelper(
        private val delegate: SupportSQLiteOpenHelper,
    ) : SupportSQLiteOpenHelper by delegate {

        // androidx.sqlite 2.5 把这两个做成了 val，不是 getXxx() 方法。
        override val writableDatabase: SupportSQLiteDatabase
            get() = TimingDatabase(delegate.writableDatabase)

        override val readableDatabase: SupportSQLiteDatabase
            get() = TimingDatabase(delegate.readableDatabase)
    }
}

/**
 * 逐次记录 `query` / `execSQL` / `compileStatement` 的真实耗时。
 *
 * 其余方法交给 Kotlin 的接口委托（`by`），一行不用写 —— 这也是选它而不是手写
 * 40 个转发方法的原因。写操作走 [compileStatement] 拿到的语句，所以语句的
 * `execute*` 也要包一层，否则「编译很快、执行卡死」这种情况测不到。
 */
private class TimingDatabase(
    private val delegate: SupportSQLiteDatabase,
) : SupportSQLiteDatabase by delegate {

    // ⚠️ query 不能走 track{}：`SupportSQLiteDatabase.query()` 返回的是**惰性 Cursor**，
    // 真正的执行（SQLiteCursor.fillWindow：跑语句、读行）发生在第一次 moveToNext/getCount，
    // 那时早就出了 track 块。只包 track 会量到「造 cursor 对象」的几毫秒，而真实耗时是几秒
    // （实测 2.89GB 库上 buildProfile 花了 36s，每条 SQL 却都显示 0ms —— 就是这么来的）。
    // 所以这里返回 [TimingCursor]，由它把「第一次碰行」当成查询结束。
    override fun query(query: String): Cursor = trackCursor(query) { delegate.query(query) }

    override fun query(query: String, bindArgs: Array<out Any?>): Cursor =
        trackCursor(query) { delegate.query(query, bindArgs) }

    // Room 生成的 DAO 查询走的是这两个重载（RoomSQLiteQuery），不重写它们等于大部分
    // 查询都没被计时 —— 这正是最需要观测的那条路径。
    override fun query(query: SupportSQLiteQuery): Cursor =
        trackCursor(query.sql) { delegate.query(query) }

    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
        trackCursor(query.sql) { delegate.query(query, cancellationSignal) }

    override fun execSQL(sql: String) = track(sql) { delegate.execSQL(sql) }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) =
        track(sql) { delegate.execSQL(sql, bindArgs) }

    override fun compileStatement(sql: String): SupportSQLiteStatement =
        TimingStatement(delegate.compileStatement(sql), sql)

    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long =
        track("INSERT INTO $table") { delegate.insert(table, conflictAlgorithm, values) }

    override fun update(
        table: String,
        conflictAlgorithm: Int,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int = track("UPDATE $table") {
        delegate.update(table, conflictAlgorithm, values, whereClause, whereArgs)
    }

    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int =
        track("DELETE FROM $table") { delegate.delete(table, whereClause, whereArgs) }

    private inline fun <T> track(sql: String, block: () -> T): T {
        val token = DbCallTracker.begin(sql)
        try {
            return block()
        } finally {
            DbCallTracker.end(token)
        }
    }

    /** 查询：token 交给 [TimingCursor]，由它决定什么时候算「查完」。 */
    private inline fun trackCursor(sql: String, block: () -> Cursor): Cursor {
        val token = DbCallTracker.begin(sql)
        return try {
            TimingCursor(block(), token)
        } catch (t: Throwable) {
            DbCallTracker.end(token)
            throw t
        }
    }
}

/**
 * 惰性 Cursor 的计时外壳。
 *
 * `SQLiteCursor` 构造时不执行语句；`fillWindow` 发生在第一次 `getCount` / `moveToNext` 之类的
 * 访问上。所以「这次查询花了多久」必须从 `query()` 一直量到**第一次拿到行**为止，
 * 而不是量到 cursor 被 new 出来为止。
 *
 * 第一次触行就调 [DbCallTracker.end] 结算；此后这个 token 不再参与判定。
 * 如果调用方从头到尾没读过（直接 close），在 [close] 里结算 —— 至少不会漏 token。
 */
private class TimingCursor(
    private val delegate: Cursor,
    private val token: Long,
) : Cursor by delegate {

    private val settled = AtomicBoolean(false)
    private val firstRowMarked = AtomicBoolean(false)

    private fun markFirstRow() {
        if (firstRowMarked.compareAndSet(false, true)) {
            DbCallTracker.markFirstRow(token)
        }
    }

    private fun settle() {
        if (settled.compareAndSet(false, true)) {
            DbCallTracker.end(token)
        }
    }

    override fun getCount(): Int = delegate.count.also { markFirstRow() }

    override fun moveToFirst(): Boolean = delegate.moveToFirst().also { markFirstRow() }

    override fun moveToNext(): Boolean = delegate.moveToNext().also { markFirstRow() }

    override fun moveToPosition(position: Int): Boolean =
        delegate.moveToPosition(position).also { markFirstRow() }

    override fun moveToLast(): Boolean = delegate.moveToLast().also { markFirstRow() }

    override fun moveToPrevious(): Boolean = delegate.moveToPrevious().also { markFirstRow() }

    override fun move(offset: Int): Boolean = delegate.move(offset).also { markFirstRow() }

    override fun close() {
        try {
            delegate.close()
        } finally {
            settle()
        }
    }
}

/** 语句执行同样计时：写操作的真正耗时在 `execute*` 上，不在 `compileStatement`。 */
private class TimingStatement(
    private val delegate: SupportSQLiteStatement,
    private val sql: String,
) : SupportSQLiteStatement by delegate {

    override fun execute() = track { delegate.execute() }

    override fun executeInsert(): Long = track { delegate.executeInsert() }

    override fun executeUpdateDelete(): Int = track { delegate.executeUpdateDelete() }

    override fun simpleQueryForLong(): Long = track { delegate.simpleQueryForLong() }

    override fun simpleQueryForString(): String? = track { delegate.simpleQueryForString() }

    private inline fun <T> track(block: () -> T): T {
        val token = DbCallTracker.begin(sql)
        try {
            return block()
        } finally {
            DbCallTracker.end(token)
        }
    }
}
