package ceui.pixiv.ui.referral

import ceui.pixiv.shaftapi.ReferralRewardDto
import ceui.pixiv.shaftapi.ReferralStateResponse

/**
 * 推介页的本地模型。
 *
 * 这一层的存在理由只有一个：**服务端是唯一真相，页面不自己算奖励**。所有判定（谁达标、
 * 能领几天、卡什么时候过期）都在 pixshaft-api 里，这里只是把那份回答翻译成页面画得出来
 * 的形状。任何「客户端先乐观地标成已领取」的做法在这一页都是错的 —— 它对应的是真的钱。
 */
internal enum class ReferralTask(val key: String, val days: Int, val target: Int = 1) {
    INVITE("invite", 7),
    RECOMMEND("recommend", 7),
    TUTORIAL("tutorial", 30),
    CIRCLE("circle", 30, 3),

    /**
     * 被邀请人的见面礼。**不出现在任务列表里** —— 它不是一件要去做的事，是首位有效邀请
     * 达成时自动到账的一张卡，只会出现在卡包中。
     */
    WELCOME("welcome", 7),
    ;

    companion object {
        /** 任务列表里真正列出来的四项，顺序就是页面顺序。 */
        val LISTED = listOf(INVITE, RECOMMEND, TUTORIAL, CIRCLE)
        fun of(key: String?): ReferralTask? = entries.firstOrNull { it.key == key }
    }
}

internal enum class ReferralStatus(val key: String) {
    NEW("new"),
    PROGRESS("progress"),
    PENDING("pending"),
    REJECTED("rejected"),
    READY("ready"),
    CLAIMED("claimed"),
    ;

    companion object {
        /** 认不出来的状态按「未开始」处理：少显示一个进度，好过画错一个可领取。 */
        fun of(key: String?): ReferralStatus = entries.firstOrNull { it.key == key } ?: NEW
    }
}

internal data class ReferralCard(
    /** 服务端的卡片 id —— 激活要用它，本地不能自己编。 */
    val id: Long,
    val task: ReferralTask,
    /** 这张卡发的档位（`pro` / `max`）。**卡面印什么以它为准**，不能写死。 */
    val plan: String,
    val days: Int,
    val claimedAt: Long,
    val expiresAt: Long,
    val activatedAt: Long = 0,
)

internal data class ReferralTaskView(
    val task: ReferralTask,
    val status: ReferralStatus,
    val progress: Int,
    val target: Int,
    /** 这项任务发的档位（`pro` / `max`）。重任务发 Max，入门任务发 Pro。 */
    val plan: String,
    val days: Int,
    /** 这一期开着没有。关着的任务照常显示条件，但不能提交也不能领。 */
    val enabled: Boolean,
    /** 被退回时审核员写的理由。空着就只显示通用文案 —— 但服务端拒绝空理由的退回。 */
    val reviewNote: String?,
    val submittedUrl: String?,
)

internal data class ReferralSnapshot(
    /** 活动开着没有。关着时页面只展示规则，所有操作按钮都不出现。 */
    val enabled: Boolean = false,
    /** 自己的邀请码与可分享链接。活动关着、或还没登录时为 null。 */
    val code: String? = null,
    val inviteUrl: String? = null,
    /** 我是被谁邀请的。非 null = 已经绑过，绑定入口不再出现（绑定一次性不可改）。 */
    val inviterUid: Long? = null,
    /** 我这条绑定被复核拒了。奖励永远不会来，页面得说一句，不能让他一直等。 */
    val inviteRejected: Boolean = false,
    val effective: Int = 0,
    val retained: Int = 0,
    /** 还没达标的邀请，和被标记等人工复核的。只有汇总，看不到好友明细。 */
    val pendingInvites: Int = 0,
    val flaggedInvites: Int = 0,
    val tasks: List<ReferralTaskView> = emptyList(),
    val cards: List<ReferralCard> = emptyList(),
    /** 自己那一档 PRO 到什么时候。新激活的卡从这里往后顺延。 */
    val activeUntil: Long = 0,
) {
    fun view(task: ReferralTask): ReferralTaskView? = tasks.firstOrNull { it.task == task }
    fun status(task: ReferralTask): ReferralStatus = view(task)?.status ?: ReferralStatus.NEW
    fun progress(task: ReferralTask): Int = view(task)?.progress ?: 0
    fun target(task: ReferralTask): Int = view(task)?.target ?: task.target
    val claimable: Int get() = tasks.count { it.status == ReferralStatus.READY }
    /** 已经领到手的总天数。卡包顶上那个大数字。 */
    val earnedDays: Int get() = cards.sumOf { it.days }
    fun unused(now: Long): Int = cards.count { it.activatedAt == 0L && it.expiresAt > now }
    fun card(task: ReferralTask): ReferralCard? = cards.firstOrNull { it.task == task }

    /**
     * 这一页上还有没有他要了结的东西：卡包里有卡，或者有已达标待领的任务。
     *
     * 活动结束之后决定「还给不给他看这一页」的就是它 —— 关一期活动的那一刻总有人
     * 手里攥着还没激活的卡（领取后有 30 天激活期），直接把页面换成「活动未开放」
     * 就等于把那些卡吞了。
     */
    val hasSomethingToSettle: Boolean
        get() = cards.isNotEmpty() || tasks.any { it.status == ReferralStatus.READY }
}

/**
 * 卡面、按钮上印的那个档位名。
 *
 * **只认写死的两档，不直接用服务端给的串。** 这几个字要塞进卡面标题和按钮里，服务端
 * 哪天返回一个长名字就会把版式挤坏；而这个版本不认识的新档位宁可退回 PRO —— 少说一
 * 个词是小事，印一个用户看不懂的标签是大事。同一条规矩在 [ceui.pixiv.shaftapi.Nana7miPlan.badgeLabel]
 * 上也用着。
 */
internal fun planLabel(plan: String?): String = when (plan) {
    "max" -> "MAX"
    else -> "PRO"
}

/** 服务端回答 → 页面模型。字段缺失一律按「服务端没说」兜底，不往上抛。 */
internal fun ReferralStateResponse.toSnapshot(): ReferralSnapshot {
    val byKey = tasks.orEmpty().mapNotNull { dto ->
        val task = ReferralTask.of(dto.key) ?: return@mapNotNull null
        task to ReferralTaskView(
            task = task,
            status = ReferralStatus.of(dto.status),
            progress = (dto.progress ?: 0).coerceAtLeast(0),
            target = (dto.target ?: task.target).coerceAtLeast(1),
            plan = dto.plan.orEmpty(),
            days = dto.days ?: task.days,
            // 服务端没说就当开着：一个老服务端不返回这个字段时，页面不该把所有任务锁死。
            enabled = dto.enabled ?: true,
            reviewNote = dto.reviewNote?.takeIf { it.isNotBlank() },
            submittedUrl = dto.submittedUrl?.takeIf { it.isNotBlank() },
        )
    }.toMap()
    return ReferralSnapshot(
        enabled = enabled == true,
        code = code?.takeIf { it.isNotBlank() },
        inviteUrl = inviteUrl?.takeIf { it.isNotBlank() },
        inviterUid = boundTo?.inviterUid?.takeIf { it > 0L },
        inviteRejected = boundTo?.reviewState == "rejected",
        effective = effective ?: 0,
        retained = retained ?: 0,
        pendingInvites = pending ?: 0,
        flaggedInvites = flagged ?: 0,
        // 按固定顺序铺开，而不是照搬服务端数组：任务顺序是版面的一部分，不该被
        // 服务端某次返回顺序改动带着走。
        tasks = ReferralTask.LISTED.mapNotNull { byKey[it] },
        cards = rewards.orEmpty().mapNotNull { it.toCard() },
        activeUntil = activeUntil ?: 0L,
    )
}

private fun ReferralRewardDto.toCard(): ReferralCard? {
    val id = id ?: return null
    // 这个版本不认识的任务（以后新加的档）直接丢掉：卡包里画一张没有名字的卡，比
    // 少画一张更糟 —— 用户会以为自己的奖励出了问题。服务端那边它照样在。
    val task = ReferralTask.of(task) ?: return null
    return ReferralCard(
        id = id,
        task = task,
        plan = plan.orEmpty(),
        days = days ?: task.days,
        claimedAt = issuedAt ?: 0L,
        expiresAt = expiresAt ?: 0L,
        activatedAt = activatedAt ?: 0L,
    )
}
