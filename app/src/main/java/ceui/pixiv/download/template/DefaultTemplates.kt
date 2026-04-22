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
    const val ILLUST  = "ShaftImages/{title}_{id}[?p>1:_p{page1}].{ext}"
    const val UGOIRA  = "ShaftImages/{title}_{id}.gif"
    // 旧版 buildPixivNovelFileName 写到 Downloads/ShaftNovels/{title}_ID{id}.txt
    // 旧版会把 title 截到 24 字符；新模板暂不做截断——超长标题会有一次性重复
    // 下载，属于已知折衷；issue 主要诉求是图片侧的命中率。
    const val NOVEL   = "ShaftNovels/{title}_ID{id}.txt"
    const val BACKUP  = "Shaft/Backups/{created:yyyyMMdd_HHmmss}.zip"
    const val LOG     = "Shaft/Logs/{created:yyyyMMdd_HHmmss}.txt"
    const val TEMP    = "ugoira/{id}/{title} {id}.{ext}"

    val SOURCES: Map<Bucket, String> = mapOf(
        Bucket.Illust    to ILLUST,
        Bucket.Ugoira    to UGOIRA,
        Bucket.Novel     to NOVEL,
        Bucket.Backup    to BACKUP,
        Bucket.Log       to LOG,
        Bucket.TempCache to TEMP,
    )

    fun compileAll(): Map<Bucket, Template> =
        SOURCES.mapValues { Template.compile(it.value) }
}
