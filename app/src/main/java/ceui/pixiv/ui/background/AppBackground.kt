package ceui.pixiv.ui.background

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.tencent.mmkv.MMKV


class AppBackground {

    private val prefStore = MMKV.mmkvWithID("shaft-session")

    private val _config = MutableLiveData<BackgroundConfig>()
    val config: LiveData<BackgroundConfig> get() = _config

    init {
        _config.value = loadFromMMKV()
    }

    private fun loadFromMMKV(): BackgroundConfig {
        val typeName = prefStore.decodeString(KEY_TYPE)
        val type =
            typeName?.let { BackgroundType.valueOf(it) } ?: BackgroundType.SPECIFIC_ILLUST

        val fileUri = prefStore.decodeString(KEY_FILE_URI).takeIf { it?.isNotEmpty() == true }
        val colorHex = prefStore.decodeString(KEY_COLOR_HEX).takeIf { it?.isNotEmpty() == true }

        return BackgroundConfig(type, fileUri, colorHex)
    }

    private fun persistToMMKV(config: BackgroundConfig) {
        prefStore.encode(KEY_TYPE, config.type.name)
        prefStore.encode(KEY_FILE_URI, config.localFileUri)
        prefStore.encode(KEY_COLOR_HEX, config.colorHexString)
    }

    fun updateConfig(newConfig: BackgroundConfig) {
        _config.value = newConfig
        persistToMMKV(newConfig)
    }

    companion object {
        private const val KEY_TYPE = "background_type"
        private const val KEY_FILE_URI = "background_file_uri"
        private const val KEY_COLOR_HEX = "background_color_hex"
    }
}
