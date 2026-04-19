package ceui.pixiv.ui.novel.reader.settings

import android.content.Context
import android.graphics.Typeface
import java.io.File

data class ReaderFont(
    val id: String,
    val displayName: String,
    val isSystem: Boolean,
    val customFontId: Int = 0,
    val customPath: String? = null,
    val builtInFamily: String? = null,
    val builtInStyle: Int = Typeface.NORMAL,
) {
    fun resolveTypeface(context: Context): Typeface {
        if (isSystem) {
            return Typeface.DEFAULT
        }
        customPath?.let { rel ->
            val file = File(context.filesDir, rel)
            if (file.exists()) {
                return runCatching { Typeface.createFromFile(file) }.getOrDefault(Typeface.DEFAULT)
            }
        }
        builtInFamily?.let {
            return Typeface.create(it, builtInStyle)
        }
        return Typeface.DEFAULT
    }
}

object PresetFonts {
    val SYSTEM = ReaderFont(id = "system", displayName = "系统默认", isSystem = true)

    val SANS = ReaderFont(
        id = "preset_sans",
        displayName = "无衬线",
        isSystem = false,
        builtInFamily = "sans-serif",
    )

    val SANS_LIGHT = ReaderFont(
        id = "preset_sans_light",
        displayName = "无衬线-细",
        isSystem = false,
        builtInFamily = "sans-serif-light",
    )

    val SANS_MEDIUM = ReaderFont(
        id = "preset_sans_medium",
        displayName = "无衬线-中",
        isSystem = false,
        builtInFamily = "sans-serif-medium",
    )

    val SERIF = ReaderFont(
        id = "preset_serif",
        displayName = "衬线（宋体风格）",
        isSystem = false,
        builtInFamily = "serif",
    )

    val MONOSPACE = ReaderFont(
        id = "preset_monospace",
        displayName = "等宽",
        isSystem = false,
        builtInFamily = "monospace",
    )

    val BUILT_IN: List<ReaderFont> = listOf(SYSTEM, SANS, SANS_LIGHT, SANS_MEDIUM, SERIF, MONOSPACE)
}
