package ceui.pixiv.sticker

import java.net.URI

data class StickerVersion(val name: String, val version: String)
data class StickerVersions(val list: List<StickerVersion>)
data class StickerResource(val width: Int, val height: Int, val url: String)
data class StickerMedia(val baseUrl: String, val resourceList: List<StickerResource>)
data class Sticker(val stickerId: Long, val name: String, val media: StickerMedia)
data class StickerGroup(val name: String, val stickerList: List<Sticker>)
data class StickerPackage(
    val width: Int, val height: Int, val url: String, val path: String,
    val size: Long, val sha256: String,
)
data class StickerPack(
    val pkgList: List<StickerPackage>, val groupList: List<StickerGroup>?,
    val stickerList: List<Sticker>?,
) {
    // Weaver duplicates animation's first group into the legacy flat list.
    fun stickers(): List<Sticker> =
        (groupList.orEmpty().flatMap { it.stickerList } + stickerList.orEmpty()).distinctBy { it.stickerId }
}
data class StickerCatalog(val versions: StickerVersions, val packs: Map<String, StickerPack>) {
    fun packages(): List<StickerPackage> = packs.values.flatMap { it.pkgList }.distinctBy { it.url }

    fun validate() {
        require(versions.list.map { it.name }.toSet() == TYPES.toSet()) { "Incomplete sticker versions" }
        require(versions.list.size == TYPES.size && versions.list.all { it.version.isNotBlank() })
        require(packs.keys == TYPES.toSet()) { "Incomplete sticker catalog" }
        val entries = packs.values.flatMap { it.pkgList }
        entries.groupBy { it.url }.values.forEach { require(it.distinct().size == 1) { "Conflicting shared ZIP" } }
        for (pack in packs.values) {
            require(pack.pkgList.map { it.width }.toSet() == setOf(64, 128)) { "Missing ZIP resolution" }
            require(pack.stickers().isNotEmpty()) { "Empty sticker pack" }
            pack.stickers().forEach { require(it.stickerId > 0 && it.media.resourceList.isNotEmpty()) }
        }
        for (pkg in entries) {
            val uri = URI(pkg.url)
            require(uri.scheme == "https" && uri.host == COS_HOST && uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null)
            // This is the legacy wire/cache identity. StickerDownloadSource resolves the
            // actual GitHub asset by checksum, including for catalogs saved by older apps.
            require(uri.path.startsWith("/public/stickers/") && uri.path.endsWith(".zip")) { "Invalid legacy sticker package URL" }
            require(pkg.size in 1..MAX_ZIP_BYTES && pkg.sha256.matches(Regex("[a-f0-9]{64}"))) { "Missing ZIP integrity information" }
            require(pkg.path.matches(Regex("(?:emoji|sticker/animation)/(?:64|128)")))
        }
    }

    companion object {
        val TYPES = listOf("customized", "static", "animation")
        const val COS_HOST = "shaft-1300933917.cos.ap-osaka.myqcloud.com"
        const val MAX_ZIP_BYTES = 128L * 1024 * 1024
    }
}

/** A dedicated Glide model: never a URL and never eligible for a network loader. */
data class LocalSticker(val stickerId: Long, val generation: String, val resourceSize: Int = 128)
