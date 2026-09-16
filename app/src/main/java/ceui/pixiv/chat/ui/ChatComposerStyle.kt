package ceui.pixiv.chat.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import ceui.lisa.databinding.ChatViewComposerBinding
import ceui.pixiv.witstudio.theme.V3Palette

/** Shared by chat and post replies so theme overlays cannot change the brand colour. */
internal fun ChatViewComposerBinding.applyChatComposerStyle() {
    val palette = chatPalette(root.context)
    val density = root.resources.displayMetrics.density
    // FilledBox reserves extra top padding for a floating label even with hintEnabled=false.
    // Normalize after inflation, including Material's large-font padding, without changing height.
    val inputVerticalPadding = etInput.paddingTop + etInput.paddingBottom
    etInput.setPaddingRelative(
        etInput.paddingStart,
        inputVerticalPadding / 2,
        etInput.paddingEnd,
        inputVerticalPadding - inputVerticalPadding / 2,
    )
    btnSend.backgroundTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(palette.primary, ColorUtils.setAlphaComponent(palette.primary, 0x40)),
    )
    btnSend.iconTint = ColorStateList.valueOf(Color.WHITE)
    replyBar.background = GradientDrawable().apply {
        cornerRadius = 16 * density
        setColor(V3Palette.withAlpha(palette.primary, if (palette.isDark) 0.14f else 0.08f))
        setStroke(maxOf(1, (0.5f * density).toInt()), V3Palette.withAlpha(palette.primary, 0.15f))
    }
    replyBarAccent.background = GradientDrawable().apply {
        cornerRadius = 999f
        setColor(palette.primary)
    }
    tvReplyBarName.setTextColor(palette.textAccent)
}
