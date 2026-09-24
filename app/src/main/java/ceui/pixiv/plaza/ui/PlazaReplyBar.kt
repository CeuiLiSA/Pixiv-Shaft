package ceui.pixiv.plaza.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import ceui.lisa.R
import ceui.lisa.databinding.ChatViewComposerBinding
import ceui.pixiv.chat.ui.applyChatComposerStyle
import ceui.pixiv.chat.ui.chatComposerSurface
import ceui.pixiv.chat.ui.chatPalette
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
        emojiPanel.setBackgroundColor(chatPalette(context).chatComposerSurface())
        emojiPanel.visibility = View.GONE
        addView(emojiPanel, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }
}
