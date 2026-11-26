package ceui.pixiv.chat.ui

import kotlin.math.roundToInt

internal data class ChatPhotoCell(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun layoutChatPhotoAlbum(
    width: Int,
    spacing: Int,
    minimumTileSize: Int,
    aspects: List<Float>,
): List<ChatPhotoCell> {
    if (width <= 0 || aspects.isEmpty()) return emptyList()
    val ratios = aspects.map { it.coerceIn(0.52f, 2.2f) }
    val gap = spacing.coerceAtMost((width - minOf(width, 3)) / 2)
    if (ratios.size == 3 && ratios[0] < 1f && width >= 3) {
        val side = width - gap
        val stacked = 1f / ratios[1] + 1f / ratios[2]
        val min = minimumTileSize.coerceAtMost(side / 2)
        val minLeft = maxOf(min, (side * 0.34f).roundToInt())
        val maxLeft = minOf(side - min, (side * 0.64f).roundToInt())
        val left = ((side * stacked + gap) * ratios[0] / (1 + stacked * ratios[0]))
            .roundToInt().coerceIn(minLeft, maxLeft)
        val right = side - left
        val height = (left / ratios[0]).roundToInt().coerceAtLeast(gap + 2)
        val minHeight = minimumTileSize.coerceIn(1, (height - gap) / 2)
        val top = ((height - gap) / ratios[1] / stacked).roundToInt().coerceIn(minHeight, height - gap - minHeight)
        return listOf(
            ChatPhotoCell(0, 0, left, height),
            ChatPhotoCell(left + gap, 0, right, top),
            ChatPhotoCell(left + gap, top + gap, right, height - gap - top),
        )
    }

    val candidates = when (ratios.size) {
        1 -> listOf(listOf(1))
        2 -> listOf(listOf(2))
        3 -> listOf(listOf(1, 2))
        4 -> listOf(listOf(2, 2))
        5 -> listOf(listOf(2, 3), listOf(3, 2))
        6 -> listOf(listOf(3, 3))
        7 -> listOf(listOf(3, 2, 2), listOf(2, 3, 2), listOf(2, 2, 3))
        8 -> listOf(listOf(2, 3, 3), listOf(3, 2, 3), listOf(3, 3, 2))
        else -> listOf(listOf(3, 3, 3))
    }
    val rowSizes = candidates.minBy { sizes ->
        var index = 0
        val heights = sizes.map { size ->
            val height = (width - gap * (size - 1)) / ratios.subList(index, index + size).sum()
            index += size
            height
        }
        heights.max() - heights.min()
    }
    val result = ArrayList<ChatPhotoCell>(ratios.size)
    var index = 0
    var top = 0
    for (size in rowSizes) {
        val row = ratios.subList(index, index + size)
        val available = width - gap * (size - 1)
        val sum = row.sum()
        val min = minimumTileSize.coerceAtMost(available / size)
        val height = (available / sum).roundToInt().coerceIn(min.coerceAtLeast(1), (width * 1.65f).toInt())
        var offset = 0
        var weight = 0f
        for ((column, ratio) in row.withIndex()) {
            weight += ratio
            val remaining = row.lastIndex - column
            val end = if (remaining == 0) available else
                (available * weight / sum).roundToInt().coerceIn(offset + min, available - min * remaining)
            result += ChatPhotoCell(offset + gap * column, top, end - offset, height)
            offset = end
        }
        index += size
        top += height + gap
    }
    return result
}
