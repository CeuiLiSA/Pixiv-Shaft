package ceui.pixiv.plaza.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import ceui.lisa.R
import ceui.lisa.databinding.ChatViewComposerBinding
import ceui.pixiv.chat.ui.applyChatComposerStyle
import ceui.pixiv.sticker.InlineStickerContainer

/** The actual chat composer, with the same inline panel below it. */
internal class PlazaReplyBar(context: Context) : LinearLayout(context) {
    val composer = ChatViewComposerBinding.inflate(LayoutInflater.from(context), this, false)
    val emojiPanel = InlineStickerContainer(composer.root.context)

    init {
        orientation = VERTICAL
        addView(composer.root)
        composer.applyChatComposerStyle()
        composer.etInput.setHint(R.string.plaza_comment_hint)
        emojiPanel.setBackgroundColor(ContextCompat.getColor(context, R.color.v3_menu_bg))
        emojiPanel.visibility = View.GONE
        addView(emojiPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }
}
