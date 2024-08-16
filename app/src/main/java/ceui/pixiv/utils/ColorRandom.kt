package ceui.pixiv.utils

import ceui.loxia.Tag

object ColorRandom {

    private val colorList = listOf(
        "#FF5C01", "#6120EE", "#1EA143", "#CA00EA", "#28C5F3",
        "#FF9E2D", "#22EAA7", "#FF59BC", "#F8D000", "#3360FF",
        "#ef9a9a", "#f48fb1", "#ba68c8", "#9575cd", "#5c6bc0",
        "#64b5f6", "#4fc3f7", "#4dd0e1", "#80cbc4", "#81c784",
        "#aed581", "#1E9E97", "#407FC5", "#6F52C1", "#B939BD",
        "#F29B41", "#FF2E7E", "#FF7335", "#ECAC16", "#6FC445",
        "#00CFA5", "#FFEC3D", "#3DFFF3", "#86FF3D", "#FFB966",
        "#ADFE7C", "#ECC9B0", "#FFBEBE", "#D1FFD3", "#EBD1FF",
        "#0AB2F6", "#080929", "#506F7C", "#717171", "#399500",
        "#B47B39", "#2D35ED", "#09B9DF", "#9C1B1B", "#877900",
        "#FF5C00", "#737AFF", "#A465FF", "#D33EFF", "#FC38CF"
    )

    fun randomColorFromTag(tag: Tag): String {
        // 如果 `Tag` 的 `name` 和 `translated_name` 都为空，直接返回默认颜色
        val identifier = tag.name ?: tag.translated_name ?: return "#000000"

        // 生成一个哈希值
        val hash = identifier.hashCode()

        // 根据哈希值对 `colorList` 的大小取模，确保哈希值总是映射到同一个颜色
        val colorIndex = (hash and Int.MAX_VALUE) % colorList.size

        return colorList[colorIndex]
    }
}