package ceui.pixiv.ui.novel.reader.model

enum class FlipMode {
    Simulation,
    Cover,
    Slide,
    None,
}

enum class ReadingDirection {
    Horizontal,
    Vertical,
}

enum class ScreenOrientation {
    Auto,
    Portrait,
    Landscape,
}

enum class ImagePlacement {
    Top,
    Center,
    Bottom,
}

enum class ImageScaleMode {
    Fit,
    Fill,
    Original,
}

enum class HighlightColor(val argb: Int) {
    Yellow(0x66FFEB3B.toInt()),
    Green(0x6681C784.toInt()),
    Pink(0x66F48FB1.toInt()),
    Blue(0x6664B5F6.toInt()),
}
