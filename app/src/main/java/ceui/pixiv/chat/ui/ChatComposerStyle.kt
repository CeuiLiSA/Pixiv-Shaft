package ceui.pixiv.chat.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import ceui.lisa.R
import ceui.lisa.databinding.ChatViewComposerBinding
import ceui.pixiv.witstudio.theme.V3Palette

/**
 * Theme-derived surface under the composer and its inline sticker panel, so the two read as
 * one bar. Chat and post replies share it.
 */
@ColorInt
internal fun V3Palette.chatComposerSurface(): Int = cardFill

/** Shared by chat and post replies so theme overlays cannot change the brand colour. */
internal fun ChatViewComposerBinding.applyChatComposerStyle() {
    val context = root.context
    val palette = chatPalette(context)
    val density = root.resources.displayMetrics.density
    val hairline = maxOf(1, (0.5f * density).toInt())
    val surface = palette.chatComposerSurface()

    // Opaque theme-tinted bar with a top hairline; it also runs under the navigation bar.
    root.background = LayerDrawable(
        arrayOf(
            GradientDrawable().apply { setColor(palette.cardHairline) },
            GradientDrawable().apply { setColor(surface) },
        ),
    ).apply { setLayerInset(1, 0, hairline, 0, 0) }

    // The field is one tonal step above the bar: theme tint over the same surface. Painted on
    // the layout itself: the edit text's transparent background stops FilledBox from ever
    // drawing its own box, so boxBackgroundColor alone left the field invisible on the bar.
    inputLayout.background = GradientDrawable().apply {
        cornerRadius = 22 * density
        setColor(
            ColorUtils.compositeColors(
                V3Palette.withAlpha(palette.primary, if (palette.isDark) 0.14f else 0.07f),
                surface,
            ),
        )
    }
    inputLayout.cursorColor = ColorStateList.valueOf(palette.textAccent)
    // Keep a disabled state: the input is locked while a public room is closed or a post
    // reply is sending, and full-strength text would read as still editable.
    val text2 = ContextCompat.getColor(context, R.color.v3_text_2)
    etInput.setTextColor(
        ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(text2, ContextCompat.getColor(context, R.color.v3_text_1)),
        ),
    )
    etInput.setHintTextColor(text2)
    etInput.highlightColor = palette.alpha30

    // FilledBox reserves extra top padding for a floating label even with hintEnabled=false.
    // Normalize after inflation, including Material's large-font padding, without changing height.
    val inputVerticalPadding = etInput.paddingTop + etInput.paddingBottom
    etInput.setPaddingRelative(
        etInput.paddingStart,
        inputVerticalPadding / 2,
        etInput.paddingEnd,
        inputVerticalPadding - inputVerticalPadding / 2,
    )

    btnEmoji.imageTintList = ColorStateList.valueOf(palette.textAccent)

    // Enabled: solid theme colour. Disabled: a tonal chip in the same hue, so an empty input
    // does not leave a muddy half-transparent disc on the bar.
    val disabled = intArrayOf(-android.R.attr.state_enabled)
    val enabled = intArrayOf()
    btnSend.backgroundTintList = ColorStateList(
        arrayOf(disabled, enabled),
        intArrayOf(ColorUtils.compositeColors(palette.alpha15, surface), palette.primary),
    )
    btnSend.iconTint = ColorStateList(
        arrayOf(disabled, enabled),
        intArrayOf(V3Palette.withAlpha(palette.textAccent, 0.45f), palette.onPrimary),
    )
    btnSend.rippleColor = ColorStateList.valueOf(V3Palette.withAlpha(palette.onPrimary, 0.24f))

    replyBar.background = GradientDrawable().apply {
        cornerRadius = 16 * density
        setColor(V3Palette.withAlpha(palette.primary, if (palette.isDark) 0.14f else 0.08f))
        setStroke(hairline, V3Palette.withAlpha(palette.primary, 0.15f))
    }
    replyBarAccent.background = GradientDrawable().apply {
        cornerRadius = 999f
        setColor(palette.primary)
    }
    tvReplyBarName.setTextColor(palette.textAccent)
}
