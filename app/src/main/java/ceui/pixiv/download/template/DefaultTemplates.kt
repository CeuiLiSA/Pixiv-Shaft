package ceui.pixiv.download.template

import ceui.pixiv.download.model.Bucket

/**
 * Default template sources per [Bucket]. These are the only place directory
 * conventions are baked in — everything else reads from [ceui.pixiv.download.config.DownloadConfig].
 *
 * 选型：默认值沿用 4.5.8 之前（下载模块重构前）的旧路径和文件名格式，这样
 * 升级用户的旧图片会被新子系统识别为「已下载」，不会在新 UI 上全部显示为
 * 「重新下载」。想要新的 `Shaft/Illusts/{author}/...` 风格可在「下载路径」
 * 设置页里选对应 preset 一键切换。
 *
 * 需要其他风格时，直接去 [ceui.pixiv.download.config.ConfigPresets] 选 preset；
 * 这里维持单一「legacy 默认」。
 */
object DefaultTemplates {

    // 旧版（<=4.5.7）的默认照片路径：Pictures/ShaftImages/{title}_{id}[_p{N+1}].{ext}
    // 注意旧版 `ShaftImages-R18` / `ShaftImages-AI` 只在用户显式开启
    // `R18DivideSave` / `AIDivideSave` 设置时才生效；默认这两个设置都关着，
    // 所有作品（含 R18/AI）都塞在 `ShaftImages/` 里。若用户想按 R18/AI 拆分，
    // 可去预设里选「R18/AI 强制分桶」。
    const val ILLUST  = "ShaftImages/{title}_{id}[?p>1:_p{page}].{ext}"
    const val UGOIRA  = "ShaftImages/{title}_{id}.gif"
    // 旧版 buildPixivNovelFileName 写到 Downloads/ShaftNovels/{title}_ID{id}.txt
    // 旧版会把 title 截到 24 字符；新模板暂不做截断——超长标题会有一次性重复
    // 下载，属于已知折衷；issue 主要诉求是图片侧的命中率。
    const val NOVEL   = "ShaftNovels/{title}_ID{id}.txt"
    // 合并下载的合集文件。文件名沿用 4.5.x 老版系列合集那套
    // `NovelSeries_<id>_Chapter_1~N_<系列名>.txt`——issue #964 的用户点名要这一版
    // （比 V3 那套 `<系列名>_合集_ID<id>` 多一个章节数）。目录另起 `Shaft/Novels/`
    // （不跟单篇小说的 legacy `ShaftNovels/` 混），也不再跟着小说模板渲染结果走，
    // 独立可改。
    const val NOVEL_SERIES = "Shaft/Novels/NovelSeries_{id}_Chapter_1~{chapters}_{series}.{ext}"

    // Caption txt for illustration/manga: separate ShaftDescriptions/ folder.
    const val CAPTION = "ShaftDescriptions/{title}_{id}.txt"
    const val BACKUP  = "Shaft/Backups/{created:yyyyMMdd_HHmmss}.zip"
    const val LOG     = "Shaft/Logs/{created:yyyyMMdd_HHmmss}.txt"
    const val TEMP    = "ugoira/{id}/{title} {id}.{ext}"

    val SOURCES: Map<Bucket, String> = mapOf(
        Bucket.Illust      to ILLUST,
        Bucket.Ugoira      to UGOIRA,
        Bucket.Novel       to NOVEL,
        Bucket.NovelSeries to NOVEL_SERIES,
        Bucket.Caption     to CAPTION,
        Bucket.Backup      to BACKUP,
        Bucket.Log         to LOG,
        Bucket.TempCache   to TEMP,
    )

    fun compileAll(): Map<Bucket, Template> =
        SOURCES.mapValues { Template.compile(it.value) }
}
