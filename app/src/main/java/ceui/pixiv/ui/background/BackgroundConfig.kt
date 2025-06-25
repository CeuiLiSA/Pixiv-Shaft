package ceui.pixiv.ui.background

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BackgroundConfig(
    val type: BackgroundType,
    val localFileUri: String? = null
) : Parcelable
