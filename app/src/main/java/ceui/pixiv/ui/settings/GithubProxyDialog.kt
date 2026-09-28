package ceui.pixiv.ui.settings

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import androidx.core.content.ContextCompat
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.http.GithubProxy
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.pixiv.sticker.StickerDownloadSource
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 「网络测试」的探测目标：`sticker-assets` release 里的目录快照 `versions.json`（213 字节）。
 *
 * 为什么是它：
 *  - **不走 GitHub API，没有配额**。未认证的 `api.github.com` 只有 60 次/小时，而加速站的出口 IP
 *    是众人共享的，一测就可能吃 403 限流 —— 那测出来的是「API 额度没了」，不是「这条加速地址不通」。
 *  - **就是 app 真正下载的那种地址形态**：`releases/download/...`（APK / AI 模型 / 表情包全走这条），
 *    所以一次探测同时回答两件事：加速站活着，且它会把这条路径代理回来。
 *  - **213 字节**：即使某些反代不流式、要把正文收完才回响应头，也几乎没有流量。
 *  - **长期有效**：这个 release 的约定是「资源更新只追加新文件，已有 ZIP 不覆盖、不删除」，
 *    目录快照同样按快照 ID 命名（`<snapshot>-versions.json`），换一批也只是多一个新文件。
 *
 * 基址取 [StickerDownloadSource.RELEASE_BASE]（与真实下载共用），只有文件名是字面量。
 */
private const val PROBE_ASSET_NAME = "cab9ab822bf2e352-versions.json"

private const val PROBE_URL: String = StickerDownloadSource.RELEASE_BASE + PROBE_ASSET_NAME

/**
 * 「github加速地址」设置项的弹窗（标题与设置项同名）。
 *
 * 弹窗内容：`不使用` / 四个内置加速站 / `自定义地址` 单选；选中即写 Settings 并关窗，
 * 写的是「已规范化的加速站根地址」，空串即 [GithubProxy.NONE]。
 *
 * 标题右侧的「网络测试」图标点一下，会对每个**内置**地址（外加用户已配的自定义地址）
 * 各打一次 GitHub 请求，结果显示在对应的行下方。图标沿用设置一级页「网络」那一档的
 * 图形（[R.drawable.ic_setcat_globe]）—— 项目里没有单独的「网络测试」图标。
 *
 * 与 PxveAPI 代理不同，这里**不需要重启**：加速地址是在每次请求前读设置的
 * （见 [GithubProxy.currentPrefix]），改完立刻对新请求生效。
 */
object GithubProxyDialog {

    /** 选项下标：0 = 不使用，1..BUILT_IN.size = 内置加速站，末位 = 自定义地址。 */
    private const val NONE_INDEX = 0
    private val CUSTOM_INDEX: Int get() = GithubProxy.BUILT_IN.size + 1

    @JvmStatic
    fun show(context: Context, onApplied: Runnable) {
        val items = ArrayList<CharSequence>(GithubProxy.BUILT_IN.size + 2)
        items.add(context.getString(R.string.github_proxy_none))
        GithubProxy.BUILT_IN.forEach { items.add(GithubProxy.displayName(it)) }
        items.add(context.getString(R.string.github_proxy_custom))

        val builder = WitDialog.CheckableDialogBuilder(context)
            .setTitle(R.string.github_proxy_title)
            .setCheckedIndex(selectedIndex(Shaft.sSettings.githubProxy))

        // 探测只活在这次弹窗里：弹窗一关，在途请求和协程一起取消。
        val scope = CoroutineScope(
            Dispatchers.Main.immediate + SupervisorJob() + CoroutineName("github-proxy-probe")
        )
        var running: GithubProxyProbe? = null
        // 弹窗是否还活着。结果晚到（取消竞态）时靠它区分「回写 UI」和「静默丢弃」。
        var dialogAlive = true

        // 设置页那一行的小字挪到了这里：标题栏右侧两颗图标 —— 网络测试 + 问号说明。
        // 问号点开才展开说明行（先例：预测性返回弹窗的 ?），平时不占留白。
        builder.setCollapsibleHint(context.getString(R.string.github_proxy_summary))

        builder.addTitleAction(
            R.drawable.ic_setcat_globe,
            context.getString(R.string.github_proxy_test_desc),
        ) {
            // 一轮没跑完时的重复点击直接忽略，免得两轮互相覆盖状态行。
            if (running != null) return@addTitleAction
            running = GithubProxyProbe(
                scope = scope,
                onResult = { index, state ->
                    if (dialogAlive) {
                        renderStatus(context, builder, index, state)
                    } else {
                        // 网络飞完回来才发现弹窗已经没了：这是取消竞态的收尾，不是异常。
                        // 结果按「已经作废」丢弃 —— 不回写已销毁的 View，更不标成失败。
                        Timber.d("github-proxy-probe: 弹窗已关闭，丢弃迟到的探测结果 index=%d", index)
                    }
                },
                onFinished = { running = null },
            ).also { it.start(testTargets()) }
        }

        builder.addTitleAction(
            R.drawable.ic_help_outline_black_24dp,
            context.getString(R.string.github_proxy_help_desc),
        ) { builder.setHintExpanded(!builder.isHintExpanded) }

        builder.addItems(items.toTypedArray()) { dialog, which ->
            if (which == CUSTOM_INDEX) {
                dialog.dismiss()
                promptCustom(context, onApplied)
            } else {
                val value = if (which == NONE_INDEX) GithubProxy.NONE
                else GithubProxy.BUILT_IN[which - 1]
                applyProxy(context, value, onApplied)
                dialog.dismiss()
            }
        }

        val dialog = builder.create()
        dialog.setOnDismissListener {
            dialogAlive = false
            running?.cancel()
            running = null
            scope.cancel()
        }
        dialog.show()
    }

    // ── 选中态 ──────────────────────────────────────────────────────

    /** 已保存地址 → 弹窗里的选中下标；自有地址（不在内置列表里）落到「自定义地址」。 */
    private fun selectedIndex(saved: String?): Int {
        val normalized = GithubProxy.normalize(saved) ?: return NONE_INDEX
        val builtIn = GithubProxy.BUILT_IN.indexOf(normalized)
        return if (builtIn >= 0) builtIn + 1 else CUSTOM_INDEX
    }

    // ── 写入 ────────────────────────────────────────────────────────

    private fun applyProxy(context: Context, value: String, onApplied: Runnable) {
        Shaft.sSettings.githubProxy = value
        Local.setSettings(Shaft.sSettings)
        Common.showToast(context.getString(R.string.string_428))
        onApplied.run()
    }

    private fun promptCustom(context: Context, onApplied: Runnable) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        val saved = GithubProxy.normalize(Shaft.sSettings.githubProxy)
        builder.setTitle(R.string.github_proxy_custom)
            .setPlaceholder(context.getString(R.string.github_proxy_custom_hint))
            // 把关方式与 PxveAPI 代理一致：能不能用交给 GithubProxy.normalize 判，
            // UI 不另写一套 startsWith("https://")，否则裸域名这类合法填法会被误拦。
            .setDefaultText(saved ?: "")
            .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            // 尾随 lambda 而不是 `addAction(text, (dialog, _) -> …)`：Kotlin 不接受把
            // lambda 写在实参括号里（那是 Java 的写法，会报 Expecting ')'）。
            .addAction(context.getString(R.string.string_142)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val raw = builder.editText.text?.toString()?.trim().orEmpty()
                val normalized = if (raw.isEmpty()) GithubProxy.NONE else GithubProxy.normalize(raw)
                if (normalized == null) {
                    Common.showToast(context.getString(R.string.github_proxy_invalid), 2)
                    return@addAction
                }
                applyProxy(context, normalized, onApplied)
                dialog.dismiss()
            }
            .create()
            .show()
    }

    // ── 网络测试 ────────────────────────────────────────────────────

    /** 探测目标：内置地址 + 用户已配的自定义地址（自己搭的站通不通，正是这个图标最该回答的）。 */
    private fun testTargets(): List<Pair<Int, String>> {
        val targets = ArrayList<Pair<Int, String>>(GithubProxy.BUILT_IN.size + 1)
        GithubProxy.BUILT_IN.forEachIndexed { index, prefix -> targets.add(index + 1 to prefix) }
        val custom = GithubProxy.normalize(Shaft.sSettings.githubProxy)
        if (custom != null && custom !in GithubProxy.BUILT_IN) targets.add(CUSTOM_INDEX to custom)
        return targets
    }

    private fun renderStatus(
        context: Context,
        builder: WitDialog.CheckableDialogBuilder,
        index: Int,
        state: ProbeState,
    ) {
        when (state) {
            ProbeState.Running -> builder.setItemStatus(
                index,
                context.getString(R.string.github_proxy_test_testing),
                ContextCompat.getColor(context, R.color.v3_text_3),
            )
            is ProbeState.Reachable -> builder.setItemStatus(
                index,
                context.getString(R.string.github_proxy_test_ok, state.ms),
                ContextCompat.getColor(context, R.color.v3_green),
            )
            is ProbeState.HttpError -> builder.setItemStatus(
                index,
                context.getString(R.string.github_proxy_test_http, state.code, state.ms),
                ContextCompat.getColor(context, R.color.v3_orange),
            )
            is ProbeState.Failed -> builder.setItemStatus(
                index,
                context.getString(R.string.github_proxy_test_fail, state.reason),
                ContextCompat.getColor(context, R.color.v3_danger),
            )
        }
    }
}

/** 单个加速地址的探测状态。 */
internal sealed interface ProbeState {
    /** 请求已发出，还没回来。 */
    object Running : ProbeState

    data class Reachable(val ms: Long) : ProbeState

    /** 连上了但状态码不是 2xx（加速站自己出错 / 被 CF 拦）。 */
    data class HttpError(val code: Int, val ms: Long) : ProbeState

    /** [reason] 用异常简名，够定位又不把长堆栈塞进一行状态里。 */
    data class Failed(val reason: String) : ProbeState
}

/**
 * 内置加速地址的连通性探测。
 *
 * 取消语义是这个类存在的理由，三条都要成立：
 *  1. **弹窗关闭就取消在途请求**：[cancel] 先取消协程，再 `cancelAll()` 掉在飞的 OkHttp Call
 *     —— 阻塞中的 `execute()` 会立刻抛 `IOException` 收尾，不会留一堆请求慢慢跑完。
 *  2. **结果晚到不算异常**：取消竞态里仍可能有一份结果回到调用方，[GithubProxyDialog] 那边
 *     发现弹窗已经销毁就把它丢掉；这里的兜底是每次 [withContext] 回来都 `ensureActive()`，
 *     协程已被取消时直接当取消处理，绝不落进 `Failed`。
 *  3. **CancellationException 原样上抛**：它先于 `IOException` / `Exception` 被 catch ，
 *     所以永远不会被下面的兜底 catch 吞成「不可达」—— 这是 Kotlin 里最常见的取消误报来源。
 */
private class GithubProxyProbe(
    private val scope: CoroutineScope,
    private val onResult: (index: Int, state: ProbeState) -> Unit,
    private val onFinished: () -> Unit,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val probeJobs = mutableListOf<Job>()
    private var finisher: Job? = null

    fun start(targets: List<Pair<Int, String>>) {
        val started = targets.map { (index, prefix) ->
            scope.launch {
                onResult(index, ProbeState.Running)
                onResult(index, probe(prefix))
            }.also { probeJobs.add(it) }
        }
        // 收尾协程只等**探测**作业：把自己也算进 joinAll 会自等成死锁。
        finisher = scope.launch {
            started.joinAll()
            onFinished()
        }
    }

    fun cancel() {
        probeJobs.forEach { it.cancel() }
        probeJobs.clear()
        finisher?.cancel()
        finisher = null
        // 协程取消打断不了阻塞中的 execute()，Call 得单独取消。
        client.dispatcher.cancelAll()
    }

    private suspend fun probe(prefix: String): ProbeState = withContext(Dispatchers.IO) {
        // 探测目标见文件头的 [PROBE_URL] 说明（sticker-assets 的目录快照，213 字节，无 API 配额）。
        val url = GithubProxy.insert(prefix, PROBE_URL)
        val began = SystemClock.elapsedRealtime()
        try {
            val request = Request.Builder().url(url).build()
            // 只等响应头：拿到状态码就 close，正文一个字节都不读。
            // 所以这次探测的流量≈HTTP 头，量的是 TTFB —— 正是一次真实下载会体感到的那个延迟；
            // 换成 HEAD 更省，但不少反代会用 405 拒掉 HEAD，那会把「能用」误报成「异常」。
            client.newCall(request).execute().use { response ->
                val ms = SystemClock.elapsedRealtime() - began
                coroutineContext.ensureActive()
                if (response.isSuccessful) ProbeState.Reachable(ms)
                else ProbeState.HttpError(response.code, ms)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // call.cancel() 造成的 IO 失败也是「被取消」，不是「不可达」：ensureActive 会把
            // 已取消的协程重新抛成 CancellationException。
            coroutineContext.ensureActive()
            ProbeState.Failed(e.javaClass.simpleName)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            ProbeState.Failed(e.javaClass.simpleName)
        }
    }
}
