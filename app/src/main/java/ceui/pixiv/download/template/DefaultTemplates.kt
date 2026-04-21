package ceui.pixiv.download.template

import ceui.pixiv.download.model.Bucket

/**
 * Default template sources per [Bucket]. These are the only place directory
 * conventions are baked in — everything else reads from [ceui.pixiv.download.config.DownloadConfig].
 */
object DefaultTemplates {

    const val ILLUST  = "Shaft/Illusts/[?R18:R18/][?AI:AI/]{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}"
    const val UGOIRA  = "Shaft/Ugoira/{author} ({author_id})/{title} {id}.gif"
    const val NOVEL   = "Shaft/Novels/{author} ({author_id})/{title} {id}.txt"
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
