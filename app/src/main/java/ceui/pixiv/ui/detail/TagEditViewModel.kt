package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.loxia.WorkEditableTag
import ceui.pixiv.chat.base.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * issue #1023「编辑标签」的状态机。[TagEditSheet] 只负责把 [state] 画出来、把点击转成方法调用,
 * 一行网络代码都不碰;真正发请求的是 [PixivTagEditOperate]。
 *
 * 这么分的实际收益:sheet 是 DialogFragment,横屏会重建,状态挂在 VM 上转屏不丢——已经拉到的
 * 标签不会重拉,提交中的请求也不会因为 view 没了就把结果丢掉(协程挂在 [viewModelScope])。
 *
 * 文案在这里就解析成字符串(走 [Shaft.getContext]),与 [ceui.pixiv.ui.account.EmailBackupV3ViewModel]
 * 的 `Effect.Toast(msg: String)` 同款,免得把一堆 resId + formatArgs 往 UiState 里塞。
 */
class TagEditViewModel(private val illustId: Long) : ViewModel() {

    /** 内容区当前该画什么。 */
    enum class Phase {
        /** 正在读标签。 */
        Loading,

        /** 读不到 / 不让编辑 / 要重新登录 —— 一句说明 + 可选一颗按钮。 */
        Blocked,

        /** 正常:标签流 + 底部动作条。 */
        Content,
    }

    /** [Phase.Blocked] 那颗按钮干什么。null = 不出按钮(纯说明,如「作者没开放编辑」)。 */
    enum class BlockedAction { Retry, WebLogin }

    data class UiState(
        val phase: Phase = Phase.Loading,
        val tags: List<WorkEditableTag> = emptyList(),
        /** [Phase.Loading] / [Phase.Blocked] 下的说明文案。 */
        val message: String? = null,
        val blockedAction: BlockedAction? = null,
        /** 有请求在飞:顶部进度线 + 输入区禁用。 */
        val busy: Boolean = false,
        /** 待确认删除的标签名;非 null 时底部换成确认条。 */
        val pendingDelete: String? = null,
    )

    sealed class Effect {
        data class Toast(val msg: String) : Effect()

        /** 标签真的变了 —— sheet 转成 fragment result,宿主详情页据此重绑标签区。 */
        object TagsChanged : Effect()

        /** 提交被接受,输入框可以清了(被 [UiState.busy] 挡下时**不**发,免得白吞用户输入)。 */
        object ClearInput : Effect()

        /** 网页登录失效,把用户送去「Web 首页」重新登录。 */
        object GoWebLogin : Effect()
    }

    private val _state = MutableLiveData(UiState())
    val state: LiveData<UiState> = _state

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 8)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private fun cur() = _state.value!!
    private fun update(block: (UiState) -> UiState) {
        _state.value = block(cur())
    }

    private fun emit(effect: Effect) {
        _effects.tryEmit(effect)
    }

    private fun string(resId: Int, vararg args: Any): String =
        Shaft.getContext().getString(resId, *args)

    init {
        // 没有网页 cookie 时连问都不用问 —— 这组接口全要登录态。
        if (!PixivTagEditOperate.hasWebSession) {
            _state.value = UiState(
                phase = Phase.Blocked,
                message = string(R.string.work_tag_edit_need_web_login),
                blockedAction = BlockedAction.WebLogin,
            )
        } else {
            load()
        }
    }

    fun load() {
        update {
            it.copy(
                phase = Phase.Loading,
                message = string(R.string.work_tag_edit_loading),
                blockedAction = null,
            )
        }
        viewModelScope.launch {
            val body = try {
                withContext(Dispatchers.IO) { PixivTagEditOperate.loadTags(illustId) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                blockOnFailure(ex, retryable = true)
                return@launch
            }
            if (!body.writable) {
                // writable=false 的两种原因(作者没开放 / 网页未登录)服务端不区分,一并说清;
                // 没有可点的动作,所以不给按钮。
                update {
                    it.copy(
                        phase = Phase.Blocked,
                        message = string(R.string.work_tag_edit_locked),
                        blockedAction = null,
                    )
                }
                return@launch
            }
            update {
                it.copy(phase = Phase.Content, tags = body.tags.orEmpty(), message = null)
            }
        }
    }

    /**
     * 提交一次增删。返回值 = 这一次有没有真的发出去,调用方据此决定要不要清输入框
     * ——被 [UiState.busy] 挡下时还照样清,用户刚打的标签就被静默吞掉了。
     *
     * 成功后重新拉一遍列表:pixiv 会对标签名做归一化(全角/半角、同义跳转),照抄用户输入
     * 回填会和服务端实际存的对不上。
     */
    private fun submit(tagName: String, add: Boolean): Boolean {
        if (cur().busy) return false
        update { it.copy(busy = true) }
        viewModelScope.launch {
            val refreshed = try {
                withContext(Dispatchers.IO) {
                    PixivTagEditOperate.editTag(illustId, tagName, add)
                    PixivTagEditOperate.loadTags(illustId)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                update { it.copy(busy = false) }
                // 提交失败不把整张 sheet 换成错误态 —— 用户的标签还在眼前,换掉等于把上下文抹了。
                // 登录失效是唯一的例外:那种情况下留在这儿也做不了任何事。
                if (PixivTagEditOperate.isAuthFailure(ex)) {
                    blockOnFailure(ex, retryable = false)
                } else {
                    emit(Effect.Toast(ex.toUserMessage(Shaft.getContext())))
                }
                return@launch
            }
            val tags = refreshed.tags.orEmpty()
            update { it.copy(busy = false, tags = tags) }
            PixivTagEditOperate.applyToPool(illustId, tags)
            emit(Effect.TagsChanged)
            emit(
                Effect.Toast(
                    string(if (add) R.string.work_tag_edit_added else R.string.work_tag_edit_deleted)
                )
            )
        }
        return true
    }

    /** 加 / 删失败落到「一句说明 + 按钮」那一屏。登录失效给「去登录」,其余给「重试」。 */
    private fun blockOnFailure(ex: Throwable, retryable: Boolean) {
        val auth = PixivTagEditOperate.isAuthFailure(ex)
        update {
            it.copy(
                phase = Phase.Blocked,
                message = if (auth) {
                    string(R.string.work_tag_edit_need_web_login)
                } else {
                    ex.toUserMessage(Shaft.getContext())
                },
                blockedAction = when {
                    auth -> BlockedAction.WebLogin
                    retryable -> BlockedAction.Retry
                    else -> null
                },
            )
        }
    }

    // ── 来自 UI 的动作 ──────────────────────────────────────────────────

    /** 输入框提交。空串忽略;成功入队才发 [Effect.ClearInput]。 */
    fun addTag(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        if (submit(name, add = true)) {
            emit(Effect.ClearInput)
        }
    }

    /** 点了可删胶囊:进入待确认态,底部换确认条。 */
    fun requestDelete(tagName: String) {
        update { it.copy(pendingDelete = tagName) }
    }

    /** 点了不可删胶囊:给一句解释而不是静默无反应。 */
    fun rejectDelete() {
        emit(Effect.Toast(string(R.string.work_tag_edit_not_deletable)))
    }

    fun cancelDelete() {
        update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val name = cur().pendingDelete ?: return
        update { it.copy(pendingDelete = null) }
        submit(name, add = false)
    }

    fun onBlockedAction() {
        when (cur().blockedAction) {
            BlockedAction.Retry -> load()
            BlockedAction.WebLogin -> emit(Effect.GoWebLogin)
            null -> Unit
        }
    }
}
