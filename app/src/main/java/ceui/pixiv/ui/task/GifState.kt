package ceui.pixiv.ui.task

import java.io.File

sealed class GifState {

    object FetchGifResponse : GifState()

    data class DownloadZip(val progress: Int) : GifState()

    object Encode : GifState()

    data class Done(val webpFile: File) : GifState()
}