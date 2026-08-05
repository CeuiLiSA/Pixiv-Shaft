package ceui.pixiv.download.model

enum class Bucket {
    Illust,
    Ugoira,
    Novel,

    /**
     * 小说系列「合并下载」产出的那一份合集文件（单系列全章节合并 / 某作者全系列
     * 合并）。和 [Novel] 分开是因为它根本不是一篇小说：没有单篇 id / 单篇标题，
     * 只有系列名 + 章节数。独立成桶后用户才能单独给它定目录（issue #964：不想被
     * 钉死在小说模板渲染出的 `{series}/` 子目录里，希望能放作者目录）和文件名。
     */
    NovelSeries,
    Backup,
    Log,
    TempCache,
}
