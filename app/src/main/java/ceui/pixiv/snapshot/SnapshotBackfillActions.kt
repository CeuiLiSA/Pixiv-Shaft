package ceui.pixiv.snapshot

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.pixiv.actionqueue.ActionHandler
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.actionqueue.RetryScope
import ceui.pixiv.actions.badPayload
import ceui.pixiv.actions.parsePayload
import ceui.pixiv.actions.toActionOutcome
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.IllustResponse
import ceui.pixiv.utils.fetchFullIllustDetail
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException

internal object SnapshotBackfillTypes {
    const val SCAN = "snapshot_backfill_scan"
    const val ILLUST = "snapshot_backfill_illust"
}

/**
 * 读一页收藏。一条只读一页，读完把下一页作为新的一条排在本页作品之后 —— 于是队列是「读 30 件、
 * 生成 30 件、再读下一页」地流式推进，长收藏不必先等几分钟把列表读完；进程被杀也只重读当前这一页。
 *
 * @param restrict `public` / `private`
 * @param nextUrl  null 表示第一页
 */
internal data class BackfillScanPayload(
    val runId: String,
    val owner: Long,
    val restrict: String,
    val nextUrl: String? = null,
)

/** 一件作品的快照。选项抄进每一行：队列里的行要能脱离当时的页面状态独立执行。 */
internal data class BackfillIllustPayload(
    val runId: String,
    val illustId: Long,
    val includeComments: Boolean,
    val includeOriginal: Boolean,
)

internal fun backfillScanRequest(runId: String, owner: Long, restrict: String, nextUrl: String? = null) =
    ActionRequest(
        type = SnapshotBackfillTypes.SCAN,
        dedupeKey = "${SnapshotBackfillTypes.SCAN}:$restrict",
        payload = Shaft.sGson.toJson(BackfillScanPayload(runId, owner, restrict, nextUrl)),
    )

internal fun backfillIllustRequest(
    runId: String,
    illustId: Long,
    includeComments: Boolean,
    includeOriginal: Boolean,
) = ActionRequest(
    type = SnapshotBackfillTypes.ILLUST,
    dedupeKey = "${SnapshotBackfillTypes.ILLUST}:$illustId",
    payload = Shaft.sGson.toJson(BackfillIllustPayload(runId, illustId, includeComments, includeOriginal)),
)

/** 一页收藏分拣的结果：要排队的作品 id，和因已有正式快照而跳过的件数。 */
internal data class BackfillPageSplit(val toQueue: List<Long>, val existing: Int)

/** 纯函数：同一页里重复的 id 只排一次，非法 id 丢掉。 */
internal fun splitBackfillPage(illustIds: List<Long>, hasFormal: (Long) -> Boolean): BackfillPageSplit {
    val toQueue = mutableListOf<Long>()
    var existing = 0
    illustIds.asSequence().filter { it > 0L }.distinct().forEach { id ->
        if (hasFormal(id)) existing++ else toQueue += id
    }
    return BackfillPageSplit(toQueue, existing)
}

/**
 * 已有自动快照能否直接转正：转正不补拉任何东西，所以自动快照得**不少于**这次要的内容 ——
 * 自动快照从不带评论；原图只能向下兼容。
 */
internal fun autoSnapshotSatisfies(
    auto: AutoSnapshotManifest,
    includeComments: Boolean,
    includeOriginal: Boolean,
): Boolean = !includeComments && (auto.includeOriginal || !includeOriginal)

/**
 * 生成失败的分类。网络类原因（含被 [SnapshotException] 包住的图片下载失败）交给
 * [toActionOutcome]：429 整队冷却、断网不扣重试次数；其余（缺图片地址、动图帧拆不开）
 * 重试也不会变好，直接判终态失败。
 */
internal fun Throwable.toBackfillOutcome(isOnline: Boolean): ActionOutcome {
    val network = generateSequence(this) { it.cause }.firstOrNull { it is HttpException || it is IOException }
    return network?.toActionOutcome(isOnline)
        ?: ActionOutcome.Fail(message ?: javaClass.simpleName, this)
}

internal class BackfillScanHandler(
    private val host: SnapshotBackfill,
    private val isOnline: () -> Boolean,
) : ActionHandler {

    override suspend fun execute(action: PendingAction): ActionOutcome {
        val payload = action.parsePayload<BackfillScanPayload>() ?: return badPayload(action)
        // 已停止 / 已开始新一轮：旧行直接收掉，不再读网络。
        if (!host.isActive(payload.runId)) return ActionOutcome.Success

        val page = try {
            val next = payload.nextUrl
            if (next == null) {
                Client.appApi.getUserBookmarkedIllusts(payload.owner, payload.restrict)
            } else {
                Shaft.sGson.fromJson(Client.appApi.generalGet(next).string(), IllustResponse::class.java)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return t.toActionOutcome(isOnline())
        }

        val split = splitBackfillPage(page.displayList.map(Illust::id), host::hasFormal)
        split.toQueue.forEach { id ->
            host.enqueue(backfillIllustRequest(payload.runId, id, host.includeComments, host.includeOriginal))
        }
        val next = page.nextPageUrl?.takeIf { it.isNotBlank() }
        if (next != null) {
            host.enqueue(backfillScanRequest(payload.runId, payload.owner, payload.restrict, next))
        }
        host.update(payload.runId) {
            it.copy(
                discovered = it.discovered + split.toQueue.size + split.existing,
                queued = it.queued + split.toQueue.size,
                existing = it.existing + split.existing,
                scanChains = if (next == null) (it.scanChains - 1).coerceAtLeast(0) else it.scanChains,
            )
        }
        return ActionOutcome.Success
    }
}

/**
 * 一件作品：跳过已有正式快照 → 自动快照够用就转正 → 否则拉完整详情再生成正式快照。
 *
 * 幂等契约：进程在生成中途被杀时，生成器会把半截目录删掉（见 [SnapshotGenerator]），重跑这一行
 * 最坏多下载一次图片；生成成功后才计数，所以不会出现「计了数却没有快照」。
 */
internal class BackfillIllustHandler(
    private val app: Context,
    private val host: SnapshotBackfill,
    private val isOnline: () -> Boolean,
) : ActionHandler {

    override suspend fun execute(action: PendingAction): ActionOutcome {
        val payload = action.parsePayload<BackfillIllustPayload>() ?: return badPayload(action)
        val runId = payload.runId
        if (!host.isActive(runId)) return ActionOutcome.Success
        val id = payload.illustId

        if (host.hasFormal(id)) {
            host.update(runId) { it.copy(existing = it.existing + 1) }
            return ActionOutcome.Success
        }
        if (app.filesDir.usableSpace < SnapshotBackfill.MIN_FREE_BYTES) {
            host.pauseForLowStorage()
            // 不扣重试次数、只推迟这一条：用户清完空间点继续，它照常排在后面再试。
            return ActionOutcome.Retry(scope = RetryScope.ACTION, countsAsAttempt = false)
        }

        val auto = AutoSnapshotRepository.listAuto(app).firstOrNull { it.manifest.illustId == id }
        if (auto != null && autoSnapshotSatisfies(auto.manifest, payload.includeComments, payload.includeOriginal)) {
            try {
                SnapshotPromoter.promote(app, auto.manifest.snapshotId)
                host.markFormal(id)
                host.update(runId) { it.copy(done = it.done + 1) }
                return ActionOutcome.Success
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 自动快照目录残缺时退回完整生成，生成成功后把这份自动快照删掉。
                Timber.tag(TAG).w(e, "promote failed, regenerate illust=%d", id)
            }
        }

        var transport: Throwable? = null
        val illust = fetchFullIllustDetail(id, toUpdate = false) { transport = it }
        if (illust == null) {
            val error = transport
            // 拿不到详情又不是网络问题，或者服务端明说 404：作品已删除 / 不公开，生成不了也不该重试。
            if (error == null || (error as? HttpException)?.code() == 404) {
                host.update(runId) { it.copy(unavailable = it.unavailable + 1) }
                return ActionOutcome.Success
            }
            return error.toActionOutcome(isOnline())
        }

        host.setCurrent(illust.title)
        try {
            SnapshotGenerator.generate(
                context = app,
                illust = illust,
                includeComments = payload.includeComments,
                includeOriginal = payload.includeOriginal,
                freshDetail = true,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "generate failed, illust=%d", id)
            return e.toBackfillOutcome(isOnline())
        } finally {
            host.setCurrent(null)
        }
        // 正式快照已经落盘，同作品的自动快照留着只会在管理页多一张重复卡。
        auto?.let { AutoSnapshotRepository.deleteAuto(app, it.manifest.snapshotId) }
        host.markFormal(id)
        host.update(runId) { it.copy(done = it.done + 1) }
        return ActionOutcome.Success
    }

    private companion object {
        const val TAG = "SnapshotBackfill"
    }
}
