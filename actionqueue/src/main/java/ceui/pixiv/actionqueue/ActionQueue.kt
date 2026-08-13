package ceui.pixiv.actionqueue

import android.content.Context
import ceui.pixiv.actionqueue.internal.db.RoomActionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * 持久化的限流动作队列。
 *
 * 用户把「要做的事」丢进来就返回，队列在后台一条一条地、按最小间隔执行，撞限流就整队冷却，
 * 进程被杀后下次启动继续跑没做完的。
 *
 * ```
 * val queue = ActionQueue.withRoomStore(
 *     context = app,
 *     handlers = mapOf(ActionTypes.ILLUST_BOOKMARK to IllustBookmarkHandler()),
 *     gate = { SessionManager.isLoggedIn },
 *     owner = { SessionManager.loggedInUid.toString() },
 * )
 * queue.start()
 * queue.enqueue(ActionRequest(type = ..., dedupeKey = "illust_bookmark:123", payload = json))
 * ```
 *
 * ## 为什么是普通 class 而不是 object
 *
 * 仓库里同类组件（QueueDownloadManager / EventReporter）都是 `object` + `init(context)`。
 * 那样写单测跑不了：全局可变状态跨用例泄漏，时间和存储都换不掉。这里所有依赖都从构造函数
 * 进来，[Clock] 和 [ActionStore] 在测试里可以整个换成假的，调度逻辑就能在纯 JVM 上用虚拟
 * 时间验证。App 侧持有单例的职责交给调用方（见 app 模块的 PixivActionQueue）。
 *
 * @param handlers 按 [ActionRequest.type] 索引的执行器。构造后不可变；找不到 type 的动作
 *                 会被直接判终态失败，而不是无声堆积。
 * @param gate     放行闸门，返回 false 时消费者只睡不取（例如未登录）。抛异常等同于 false。
 * @param owner    归属标识提供者，通常是当前登录用户 id。入队时记在行上，取行时按它过滤 ——
 *                 库跨登录态持久，不分归属的话 A 没发完的收藏会用 B 的 token 发出去。
 * @param scope    队列自己的生命周期。默认是进程级 IO scope；测试传 TestScope。
 * @param onError  存储层 / 入队泵出错时的回调。模块不依赖 Timber，日志交给调用方。
 */
public class ActionQueue(
    private val store: ActionStore,
    handlers: Map<String, ActionHandler>,
    private val policy: QueuePolicy = QueuePolicy(),
    private val clock: Clock = Clock.SYSTEM,
    private val gate: suspend () -> Boolean = { true },
    private val owner: suspend () -> String = { "" },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val random: Random = Random.Default,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    private val onCapacityPruned: (Int) -> Unit = {},
) {

    private val handlers: Map<String, ActionHandler> = handlers.toMap()

    /**
     * 「再看一眼队列」的唤醒信号。CONFLATED：连点 50 次爱心只需要唤醒一次，
     * 攒着的信号没有意义。
     *
     * 消费循环不用 Room 的 `Flow` 驱动 —— 同仓 DownloadQueueDao 的注释记过这个坑：
     * 高频 UPDATE 下 InvalidationTracker 会在首次 emit 之后静默不再触发。
     */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /**
     * 入队请求的排队通道。单个消费协程保证写库顺序**严格等于**调用顺序。普通业务队列默认
     * UNLIMITED；配置了 [QueuePolicy.maxStoredActions] 的有界后台队列也用同一个上限约束尚未
     * 落库的内存积压，避免 Room 卡顿时只限制了磁盘、内存通道却继续无限增长。
     *
     * 不能让 [enqueue] 各自 `scope.launch` 去写库：那些协程落在多线程的 IO 池上，
     * 连点两下爱心（收藏、取消）时后发的那条可能先落库，随后先发的那条再把它合并掉，
     * 结果队列发出的是「收藏」而界面显示的是「未收藏」，且永远不会自愈。
     */
    private val enqueueRequests = Channel<ActionRequest>(
        capacity = policy.maxStoredActions ?: Channel.UNLIMITED,
    )

    /** [enqueueAndWait] 与入队泵共用，保证两条路径的写库互不交错。 */
    private val enqueueMutex = Mutex()

    private val _events = MutableSharedFlow<ActionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * 执行结果广播。UI 订阅它来回滚乐观更新 —— 注意只有 [ActionEvent.Failed] 才该回滚，
     * [ActionEvent.Retrying] 还会再试，此时回滚会让界面来回跳。
     */
    public val events: SharedFlow<ActionEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<QueueState>(QueueState.Idle)
    public val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _paused = MutableStateFlow(false)
    public val isPaused: StateFlow<Boolean> = _paused.asStateFlow()

    private var loopJob: Job? = null

    /** 下一条最早可以开始执行的时刻，节流用。冷启动为 0 ⇒ 首条立即执行，不平白等一个间隔。 */
    private var nextAllowedAtMs: Long = 0L

    /**
     * 整队冷却截止时刻，撞到可重试失败时推高。写内存的同时落库（见
     * [ActionStore.loadCooldownUntilMs]）—— 冷却动辄几分钟，进程在这个窗口里被回收是常态。
     */
    private var cooldownUntilMs: Long = 0L

    init {
        scope.launch {
            for (request in enqueueRequests) {
                try {
                    enqueueAndWait(request)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // 写库失败只丢这一条，泵必须活着：它死了之后所有点击都会静默消失，
                    // 而界面上的乐观状态还在。
                    onError("enqueue failed: ${request.dedupeKey}", t)
                }
            }
        }
    }

    /** 启动消费循环。幂等，重复调用无副作用。 */
    @Synchronized
    public fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            try {
                // 上次进程被杀时留下的 RUNNING 复位。代价是那条可能被执行两次，
                // 这正是 ActionHandler 要求幂等的原因。
                store.resurrectRunning()
                enforceCapacity(currentOwner())
                restoreCooldown()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                onError("queue startup recovery failed", t)
            }
            runLoop()
        }
    }

    /** 停止消费循环。在手的那条会被归还成 PENDING，下次 [start] 继续。 */
    @Synchronized
    public fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * 入队，立即返回。写库在 [scope] 上异步做，调用方（通常是 UI 线程）不阻塞，
     * 但多次调用之间的**先后顺序被保证**，见 [enqueueRequests]。
     */
    public fun enqueue(request: ActionRequest) {
        val result = enqueueRequests.trySend(request)
        if (result.isFailure) {
            if (policy.maxStoredActions != null && result.exceptionOrNull() == null) {
                notifyCapacityPruned(1)
            } else {
                onError(
                    "enqueue channel rejected: ${request.dedupeKey}",
                    result.exceptionOrNull() ?: IllegalStateException("channel full or closed"),
                )
            }
        }
    }

    /** 入队并等待落库，返回行 id。需要确知已持久化时用它。 */
    public suspend fun enqueueAndWait(request: ActionRequest): Long = enqueueMutex.withLock {
        val who = currentOwner()
        val id = store.enqueue(request, clock.nowMs(), who)
        enforceCapacity(who)
        kick()
        id
    }

    private suspend fun enforceCapacity(who: String) {
        policy.maxStoredActions?.let { maxRows ->
            val removed = store.pruneToMaxRows(who, maxRows)
            if (removed > 0) notifyCapacityPruned(removed)
        }
    }

    private fun notifyCapacityPruned(removed: Int) {
        try {
            onCapacityPruned(removed)
        } catch (callbackError: Throwable) {
            onError("capacity-pruned callback failed", callbackError)
        }
    }

    /**
     * 踢一脚消费循环：立刻重新评估一次，不等 [QueuePolicy.idlePollMs] 的兜底轮询。
     *
     * **闸门条件恢复时（网络回来、登录完成）用这个，不要用 [resume]。** 两者都会唤醒循环，
     * 但 [resume] 还会把暂停标志清零 —— 那意味着用户主动按下的「暂停队列」会被一次网络抖动
     * 顺手解除掉。区分开的成本只有这一个方法。
     */
    public fun kick() {
        wakeups.trySend(Unit)
    }

    public fun pause() {
        _paused.value = true
        kick()
    }

    /** 解除 [pause]。只想唤醒循环、不想动暂停标志的话用 [kick]。 */
    public fun resume() {
        _paused.value = false
        kick()
    }

    /** 把当前归属下所有终态失败的行重新排队。@return 重置了几条。 */
    public suspend fun retryAllFailed(): Int {
        val count = store.retryAllFailed(clock.nowMs(), currentOwner())
        setCooldown(0L)
        kick()
        return count
    }

    /**
     * 丢弃一条动作。调用方处理完 [ActionEvent.Failed]（回滚 + 提示）之后用它把行删掉，
     * 否则 FAILED 会在库里长期堆积 —— 每行还带着最长 500 字的错误文本。
     */
    public suspend fun forget(actionId: Long) {
        store.delete(actionId)
    }

    public suspend fun clearFailed(): Int = store.clearFailed(currentOwner())

    /** 给无需 UI 回滚的后台队列提供有界死信保留，避免失败行无限撑大本地库。 */
    public suspend fun pruneFailed(keepLatest: Int): Int =
        store.pruneFailed(currentOwner(), keepLatest.coerceAtLeast(0))

    /** 对当前 owner 执行一次全状态容量裁剪。主要供启动恢复和运维入口显式调用。 */
    public suspend fun pruneToMaxRows(maxRows: Int): Int =
        store.pruneToMaxRows(currentOwner(), maxRows.coerceAtLeast(1))

    public suspend fun pendingCount(): Int = store.pendingCount(currentOwner())

    public suspend fun failedCount(): Int = store.failedCount(currentOwner())

    private suspend fun runLoop() {
        while (coroutineContext.isActive) {
            val sleepMs = try {
                step()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 存储层炸了（磁盘满、库损坏）不能把消费者一起带走：那样这个进程剩下的
                // 时间里每一次收藏都只写了乐观状态却永远发不出去，而且异常还会顺着没有
                // CoroutineExceptionHandler 的 scope 冒到默认处理器上崩掉进程。
                // 退一步，睡一轮再试 —— 磁盘满这类故障是可恢复的。
                onError("queue step failed", t)
                recoverRunningAfterStepFailure()
                policy.idlePollMs
            }
            if (sleepMs > 0L) {
                withTimeoutOrNull(sleepMs) { wakeups.receive() }
            }
        }
    }

    /**
     * 推进一步。
     * @return 建议休眠的毫秒数；0 表示立刻进行下一轮。
     */
    private suspend fun step(): Long {
        val who = currentOwner()
        val pending = store.pendingCount(who)

        if (_paused.value || !gateAllows()) {
            _state.value = QueueState.Suspended(pending)
            return policy.idlePollMs
        }

        val now = clock.nowMs()

        if (now < cooldownUntilMs) {
            _state.value = QueueState.CoolingDown(cooldownUntilMs, pending)
            return cooldownUntilMs - now
        }

        if (now < nextAllowedAtMs) {
            _state.value = QueueState.Working(pending)
            return nextAllowedAtMs - now
        }

        val stored = store.nextRunnable(now, who)
        if (stored == null) {
            _state.value = if (pending == 0) QueueState.Idle else QueueState.Working(pending)
            // 有 PENDING 但都还没到 notBefore：睡到最早那条到点为止，别被兜底轮询拖慢。
            val earliest = store.earliestNotBeforeMs(who)
            return if (earliest != null && earliest > now) {
                minOf(earliest - now, policy.idlePollMs)
            } else {
                policy.idlePollMs
            }
        }

        _state.value = QueueState.Working(pending)
        execute(stored)
        return 0L
    }

    private suspend fun currentOwner(): String =
        try {
            owner()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            onError("owner provider failed", t)
            ""
        }

    private suspend fun gateAllows(): Boolean =
        try {
            gate()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // 闸门自己炸了就当关着 —— 放行反而可能让一整队请求打到未登录状态上全军覆没。
            false
        }

    private suspend fun execute(stored: StoredAction) {
        val action = stored.action
        val handler = handlers[action.type]
        if (handler == null) {
            // 注册表里没有这个 type：多半是版本回退后读到了新版写入的行。
            // 判终态失败而不是留在队里，否则它会永远排在队首挡住后面所有动作。
            val reason = "no handler registered for type=${action.type}"
            store.markFailed(action.id, reason)
            emitFailed(action, reason, null)
            return
        }

        store.markRunning(action.id)

        val outcome = try {
            handler.execute(action)
        } catch (ce: CancellationException) {
            // 队列被 stop() 或进程在退出：把在手的这条原样还回去（不算一次失败尝试）。
            // 必须 NonCancellable，否则这次写库会被同一个取消信号打断，行就烂在 RUNNING 上。
            withContext(NonCancellable) { store.releaseToPending(action.id) }
            throw ce
        } catch (t: Throwable) {
            // handler 没自己分类就统一按可重试处理，宁可多试几次也别静默丢掉用户的收藏。
            ActionOutcome.Retry(cause = t)
        }

        // 节流点：无论成败，下一条都要等够间隔。失败时更要等 —— 多半是被限流了。
        nextAllowedAtMs = clock.nowMs() + maxOf(policy.minGapMs, stored.gapMs)

        var finalStatePersisted = false
        try {
            when (outcome) {
                is ActionOutcome.Success -> {
                    store.delete(action.id)
                    finalStatePersisted = true
                    setCooldown(0L)
                    _events.tryEmit(ActionEvent.Succeeded(action))
                }

                is ActionOutcome.Fail -> {
                    store.markFailed(action.id, outcome.reason)
                    finalStatePersisted = true
                    emitFailed(action, outcome.reason, outcome.cause)
                }

                is ActionOutcome.Retry -> {
                    val attemptsMade = action.attempt + if (outcome.countsAsAttempt) 1 else 0
                    if (outcome.countsAsAttempt && attemptsMade >= policy.maxAttempts) {
                        val reason = "failed after $attemptsMade attempts: " +
                            (outcome.cause?.message ?: "unknown error")
                        store.markFailed(action.id, reason)
                        finalStatePersisted = true
                        emitFailed(action, reason, outcome.cause)
                    } else {
                        val cooldown = computeCooldownMs(
                            priorAttempts = action.attempt,
                            policy = policy,
                            retryAfterMs = outcome.retryAfterMs,
                            random = random,
                        )
                        val retryAt = clock.nowMs() + cooldown
                        // 429 是账号级速率限制，只退避失败那条、后面的照发只会接着撞，所以整队一起冻；
                        // 而 400 / 401 这种只说明这一条不行，冻整队等于让一条坏行连累别人的收藏。
                        if (outcome.scope == RetryScope.QUEUE) {
                            setCooldown(retryAt)
                        }
                        store.rescheduleForRetry(action.id, retryAt, outcome.countsAsAttempt)
                        finalStatePersisted = true
                        _events.tryEmit(ActionEvent.Retrying(action, retryAt, outcome.cause))
                    }
                }
            }
        } catch (ce: CancellationException) {
            if (!finalStatePersisted) {
                withContext(NonCancellable) { releaseAfterPersistenceFailure(action, ce) }
            }
            throw ce
        } catch (t: Throwable) {
            if (!finalStatePersisted) releaseAfterPersistenceFailure(action, t)
            throw t
        }
    }

    /**
     * handler 已经结束，但 SUCCESS/FAILED/PENDING 的最终写库失败：此时最危险的是把行永久留在
     * RUNNING。event handler 必须幂等，所以宁可归还 PENDING 再发一次，也不能等到下次冷启动。
     */
    private suspend fun releaseAfterPersistenceFailure(action: PendingAction, cause: Throwable) {
        onError("persist outcome failed, releasing action id=${action.id}", cause)
        try {
            store.releaseToPending(action.id)
        } catch (releaseError: Throwable) {
            onError("release RUNNING action failed id=${action.id}", releaseError)
        }
    }

    /**
     * 如果上面的归还也撞上短暂存储故障，消费循环每次出错后都再扫一次 RUNNING。数据库一恢复，
     * 无需杀进程就能把孤儿行复位；同一循环串行执行，因此这里不会抢走仍在 handler 中的动作。
     */
    private suspend fun recoverRunningAfterStepFailure() {
        try {
            store.resurrectRunning()
        } catch (ce: CancellationException) {
            throw ce
        } catch (recoveryError: Throwable) {
            onError("in-process RUNNING recovery failed", recoveryError)
        }
    }

    /**
     * 广播终态失败，并带上「这条是不是已经被更新的意图顶掉了」。
     *
     * 查一次库而不是让 UI 去比较当前值：收藏→取消→收藏之后，当前值和失败那条恰好相等，
     * 比值的判据会误判成「可以回滚」，把用户还没发出去的最新意图覆盖掉。
     */
    private suspend fun emitFailed(action: PendingAction, reason: String, cause: Throwable?) {
        val superseded = try {
            store.hasPending(action.dedupeKey, action.owner, action.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            onError("hasPending failed: ${action.dedupeKey}", t)
            false
        }
        _events.tryEmit(ActionEvent.Failed(action, reason, cause, superseded))
    }

    /**
     * 读回上次进程留下的整队冷却，并钳掉墙钟错乱写出来的离谱值。
     *
     * 落库的是**绝对时刻**，而 [Clock.SYSTEM] 是墙钟。RTC 失效的设备开机时常带一个偏前的
     * 时间（厂商固件里那种 2036 年），此时撞一次 429 写下的截止时刻就是「偏前的时间 + 冷却」；
     * 随后 NTP 把时钟校回来，`now < cooldownUntilMs` 从此恒真。
     *
     * 而清零冷却只有两条路：某条动作执行成功，或调用方主动 [retryAllFailed] —— 前者被冻着就
     * 不可能发生，于是队列静默停摆，用户每一次收藏都只是变红然后石沉大海。所以这里按
     * [QueuePolicy.maxPossibleCooldownMs] 钳一刀：合法写下的值一个都不会被削短（它们按定义
     * 就在这个上界之内），而错乱的值最多只能再冻这么久。
     *
     * 钳完**必须写回库**。只改内存的话，下次启动会照着库里那个坏值重新钳出一个新的截止时刻，
     * 于是每次冷启动都白冻一轮，永远等不到那次能把它清零的成功。写回去之后这个值是绝对的，
     * 真实时间一过就自愈。
     */
    private suspend fun restoreCooldown() {
        val loaded = store.loadCooldownUntilMs()
        val ceiling = clock.nowMs() + policy.maxPossibleCooldownMs
        if (loaded > ceiling) {
            onError(
                "cooldown until $loaded is beyond any value this queue could write, clamping",
                IllegalStateException("bogus persisted cooldown"),
            )
            setCooldown(ceiling)
        } else {
            cooldownUntilMs = loaded
        }
    }

    private suspend fun setCooldown(untilMs: Long) {
        // 每条动作成功都会把冷却清零，值没变就别写库 —— 那是每次收藏一次多余的写事务。
        if (cooldownUntilMs == untilMs) return
        cooldownUntilMs = untilMs
        store.saveCooldownUntilMs(untilMs)
    }

    public companion object {

        /**
         * 装配一个 Room 持久化的队列。[databaseName] 默认是本模块私有的
         * `pixiv_action_queue.db`；调用方也可为完全独立的队列指定另一个库名。
         * 两者都与 app 主库隔离。
         */
        public fun withRoomStore(
            context: Context,
            handlers: Map<String, ActionHandler>,
            databaseName: String = "pixiv_action_queue.db",
            policy: QueuePolicy = QueuePolicy(),
            clock: Clock = Clock.SYSTEM,
            gate: suspend () -> Boolean = { true },
            owner: suspend () -> String = { "" },
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onError: (String, Throwable) -> Unit = { _, _ -> },
            onCapacityPruned: (Int) -> Unit = {},
        ): ActionQueue = ActionQueue(
            store = RoomActionStore(context.applicationContext, databaseName),
            handlers = handlers,
            policy = policy,
            clock = clock,
            gate = gate,
            owner = owner,
            scope = scope,
            onError = onError,
            onCapacityPruned = onCapacityPruned,
        )
    }
}
