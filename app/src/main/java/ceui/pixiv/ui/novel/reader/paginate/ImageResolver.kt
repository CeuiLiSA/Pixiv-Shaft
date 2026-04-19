package ceui.pixiv.ui.novel.reader.paginate

import ceui.loxia.NovelImages
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * Maps content tokens referencing Pixiv assets to concrete URLs using the
 * embedded object map from [WebNovel]. Returned as a lambda so [Paginator] can
 * stay oblivious to the data source.
 */
object ImageResolver {

    fun of(webNovel: WebNovel): (ContentToken) -> String? = resolver@{ token ->
        when (token) {
            is ContentToken.UploadedImage -> {
                val urls = webNovel.images?.get(token.imageId.toString())?.urls ?: return@resolver null
                urls[NovelImages.Size.Size1200x1200]
                    ?: urls[NovelImages.Size.SizeOriginal]
                    ?: urls[NovelImages.Size.Size480mw]
                    ?: urls[NovelImages.Size.Size240mw]
                    ?: urls.values.firstOrNull()
            }

            is ContentToken.PixivImage -> {
                val key = if (token.pageIndex > 0) "${token.illustId}-${token.pageIndex}" else token.illustId.toString()
                val direct = webNovel.illusts?.get(key)?.illust?.images
                val fallback = if (direct == null) webNovel.illusts?.get(token.illustId.toString())?.illust?.images else null
                val urls = direct ?: fallback
                urls?.medium ?: urls?.large ?: urls?.original ?: urls?.small ?: urls?.url
            }

            else -> null
        }
    }
}
