package ceui.pixiv.ui.novel.reader.settings

import android.graphics.Color
import androidx.annotation.ColorInt

data class ReaderTheme(
    val id: String,
    val displayName: String,
    @ColorInt val backgroundColor: Int,
    @ColorInt val textColor: Int,
    @ColorInt val secondaryTextColor: Int,
    @ColorInt val accentColor: Int,
    @ColorInt val linkColor: Int,
    @ColorInt val selectionColor: Int,
    @ColorInt val highlightColor: Int,
    @ColorInt val dividerColor: Int,
    @ColorInt val chapterTitleColor: Int,
    val isDark: Boolean,
    val backgroundImagePath: String? = null,
) {
    companion object {
        val WHITE = ReaderTheme(
            id = "preset_white",
            displayName = "纯白",
            backgroundColor = 0xFFFFFFFF.toInt(),
            textColor = 0xFF333333.toInt(),
            secondaryTextColor = 0xFF888888.toInt(),
            accentColor = 0xFF5B6EFF.toInt(),
            linkColor = 0xFF2E7BFF.toInt(),
            selectionColor = 0x665B6EFF,
            highlightColor = 0x66FFEB3B,
            dividerColor = 0xFFE4E4E4.toInt(),
            chapterTitleColor = 0xFF222222.toInt(),
            isDark = false,
        )

        val EYE_PROTECTION = ReaderTheme(
            id = "preset_eye_protection",
            displayName = "护眼",
            backgroundColor = 0xFFC7EDCC.toInt(),
            textColor = 0xFF2E3A2F.toInt(),
            secondaryTextColor = 0xFF587159.toInt(),
            accentColor = 0xFF2F7A49.toInt(),
            linkColor = 0xFF1B5E20.toInt(),
            selectionColor = 0x662F7A49,
            highlightColor = 0x66FFD54F,
            dividerColor = 0xFFA7D5AC.toInt(),
            chapterTitleColor = 0xFF1F3020.toInt(),
            isDark = false,
        )

        val PARCHMENT = ReaderTheme(
            id = "preset_parchment",
            displayName = "羊皮纸",
            backgroundColor = 0xFFF5E8CF.toInt(),
            textColor = 0xFF5A4A38.toInt(),
            secondaryTextColor = 0xFF8A7558.toInt(),
            accentColor = 0xFF8B6B3D.toInt(),
            linkColor = 0xFF6B4A2F.toInt(),
            selectionColor = 0x668B6B3D,
            highlightColor = 0x66FFCA28,
            dividerColor = 0xFFD4C094.toInt(),
            chapterTitleColor = 0xFF3E2F1F.toInt(),
            isDark = false,
        )

        val BUTTER = ReaderTheme(
            id = "preset_butter",
            displayName = "牛油纸",
            backgroundColor = 0xFFFDF6E3.toInt(),
            textColor = 0xFF5C5343.toInt(),
            secondaryTextColor = 0xFF9C8E72.toInt(),
            accentColor = 0xFFB58900.toInt(),
            linkColor = 0xFFCB7B00.toInt(),
            selectionColor = 0x66B58900,
            highlightColor = 0x66FFEE58,
            dividerColor = 0xFFEEE2C4.toInt(),
            chapterTitleColor = 0xFF3D3527.toInt(),
            isDark = false,
        )

        val NIGHT = ReaderTheme(
            id = "preset_night",
            displayName = "夜间",
            backgroundColor = 0xFF1B1B1B.toInt(),
            textColor = 0xFFBDBDBD.toInt(),
            secondaryTextColor = 0xFF7E7E7E.toInt(),
            accentColor = 0xFF64B5F6.toInt(),
            linkColor = 0xFF90CAF9.toInt(),
            selectionColor = 0x6664B5F6,
            highlightColor = 0x66FFEE58,
            dividerColor = 0xFF2E2E2E.toInt(),
            chapterTitleColor = 0xFFE0E0E0.toInt(),
            isDark = true,
        )

        val CHARCOAL = ReaderTheme(
            id = "preset_charcoal",
            displayName = "炭黑",
            backgroundColor = 0xFF000000.toInt(),
            textColor = 0xFF9E9E9E.toInt(),
            secondaryTextColor = 0xFF616161.toInt(),
            accentColor = 0xFFFF7043.toInt(),
            linkColor = 0xFFFFAB91.toInt(),
            selectionColor = 0x66FF7043,
            highlightColor = 0x66FFEE58,
            dividerColor = 0xFF1E1E1E.toInt(),
            chapterTitleColor = 0xFFBDBDBD.toInt(),
            isDark = true,
        )

        val PRESETS: List<ReaderTheme> = listOf(
            WHITE, EYE_PROTECTION, PARCHMENT, BUTTER, NIGHT, CHARCOAL,
        )

        fun findPresetById(id: String): ReaderTheme? = PRESETS.firstOrNull { it.id == id }
    }
}
