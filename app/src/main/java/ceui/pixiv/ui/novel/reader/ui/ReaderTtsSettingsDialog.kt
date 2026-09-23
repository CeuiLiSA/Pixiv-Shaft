package ceui.pixiv.ui.novel.reader.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import ceui.lisa.R
import ceui.lisa.databinding.ItemReaderSettingSwitchBinding
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.tts.NovelTtsController
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogView
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.rowSurface
import ceui.pixiv.witstudio.theme.v3Font

fun showReaderTtsSettings(context: Context) {
    object : WitDialog.CustomDialogBuilder(context) {
        override fun onCreateContent(dialog: WitDialog, parent: WitDialogView, context: Context): View {
            val rows = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(16), 0, context.dp(16), 0)
            }
            val speed = context.label("", 15f, 500).apply {
                minHeight = context.dp(56)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
                lineHeightRatio(1.4f)
                background = context.rowSurface(0, 5)
                isFocusable = true
            }
            fun refreshSpeed() {
                speed.text = context.getString(R.string.reader_menu_tts_speed) + " · " +
                    context.getString(R.string.reader_tts_speed_value, ReaderSettings.ttsSpeed)
            }
            refreshSpeed()
            speed.setOnClickListener {
                val options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                WitDialog.CheckableDialogBuilder(context)
                    .setTitle(R.string.reader_menu_tts_speed)
                    .setCheckedIndex(options.indexOf(ReaderSettings.ttsSpeed))
                    .addItems(options.map { context.getString(R.string.reader_tts_speed_value, it) }.toTypedArray()) { menu, index ->
                        ReaderSettings.ttsSpeed = options[index]
                        val playback = NovelTtsController.playbackState
                        if (playback.isActive) playback.sessionId?.let {
                            NovelTtsController.setSpeed(context, it, options[index])
                        }
                        refreshSpeed()
                        menu.dismiss()
                    }.show()
            }
            rows.addView(speed, LinearLayout.LayoutParams(-1, -2))
            fun toggle(index: Int, label: Int, checked: Boolean, update: (Boolean) -> Unit) {
                val row = ItemReaderSettingSwitchBinding.inflate(LayoutInflater.from(context), rows, false)
                row.labelText.text = context.getString(label)
                row.labelText.typeface = context.v3Font(400)
                row.labelText.lineHeightRatio(1.4f)
                row.switchControl.contentDescription = row.labelText.text
                row.switchControl.isChecked = checked
                row.switchControl.setOnCheckedChangeListener { _, value -> update(value) }
                row.root.background = context.rowSurface(index, 5)
                row.root.setOnClickListener { row.switchControl.toggle() }
                rows.addView(row.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(2) })
            }
            toggle(1, R.string.reader_tts_highlight, ReaderSettings.ttsHighlight) { ReaderSettings.ttsHighlight = it }
            toggle(2, R.string.reader_tts_auto_page, ReaderSettings.ttsAutoPage) { ReaderSettings.ttsAutoPage = it }
            toggle(3, R.string.reader_tts_double_tap, ReaderSettings.ttsDoubleTap) { ReaderSettings.ttsDoubleTap = it }
            toggle(4, R.string.reader_tts_show_page_action, ReaderSettings.ttsShowPageAction) { ReaderSettings.ttsShowPageAction = it }
            rows.addView(context.label(context.getString(R.string.reader_tts_follow_hint), 13f,
                color = context.getColor(R.color.v3_text_2)).apply {
                lineHeightRatio(1.5f)
                setPadding(context.dp(4), context.dp(16), context.dp(4), context.dp(4))
            })
            return wrapWithScroll(rows)
        }
    }.setTitle(R.string.reader_tts_settings)
        .addAction(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
        .show()
}
