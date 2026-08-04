package ceui.pixiv.download.model

enum class Flag {
    R18,
    AI,
    Original,
    Animated,
    /** 作品属于某个系列（小说连载等），供模板条件块 [?series:…] 使用。 */
    Series,
}
