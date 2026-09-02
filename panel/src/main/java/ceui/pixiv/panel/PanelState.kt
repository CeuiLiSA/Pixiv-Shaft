package ceui.pixiv.panel

/** Three-state model for input area + bottom panel screens. */
enum class PanelState {
    /** Nothing below the input bar except the navigation bar. */
    NONE,
    /** The soft keyboard is visible. */
    KEYBOARD,
    /** A custom bottom panel (emoji, sticker, voice, etc.) is visible. */
    PANEL,
}
