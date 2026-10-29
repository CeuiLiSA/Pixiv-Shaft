package ceui.pixiv.ui.common

/**
 * 内容过滤链（[IllustFeedItem.passesContentFilters] / [NovelFeedItem.of]）里「哪几条把作品滤掉了」。
 *
 * 只服务一个场景：作者作品页整页被滤空时，空态要说明**本页实际命中**了哪几条过滤
 * （见 `ceui.pixiv.ui.user.filteredEmptyStateText`）。它刻意不等于「设置里开着的开关」——
 * 开着的开关未必真的滤掉了东西，列出来只会误导。
 *
 * [INVISIBLE] 与 [MUTED_USER] 只是过滤链的完整镜像，不是用户可关的设置项
 * （前者是作品自身的 visible 标记，后者在作者作品页被 `skipMuteUserFilter` 让步），
 * 空态清单会跳过它们。
 */
enum class ContentFilterReason {
    /** 失效作品（非公开 / 限定 / 无法显示，见 [ceui.pixiv.api.model.Illust.isUnavailable]）。非用户设置项，不进空态清单。 */
    INVISIBLE,

    /** 命中「屏蔽标签」。 */
    MUTED_TAG,

    /** 命中「屏蔽作者」。作者作品页对该条让步，实际不会出现。 */
    MUTED_USER,

    /** 命中全局「默认过滤 R18 内容」。 */
    R18,

    /** 命中全局「不显示 AI 生成的作品」，且强度为「完全不显示」（模糊粒子化不剔除，不算）。 */
    AI,

    /** 命中小说「列表自动屏蔽」（正文字数区间 / 超长标签名阈值）。 */
    NOVEL_SPAM,
}
