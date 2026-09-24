package ceui.pixiv.chat.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.setFragmentResult
import ceui.lisa.R
import ceui.lisa.databinding.ChatItemMessageActionBinding
import ceui.lisa.databinding.ChatSheetMessageActionsBinding
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.v3Font

/**
 * Bottom sheet presenting contextual actions for a chat message:
 * copy, reply, forward, delete.
 *
 * Communicates the chosen action back to the host fragment via
 * the Fragment Result API ([REQUEST_KEY] / [RESULT_ACTION]).
 *
 * Sits on [WitBottomSheet]'s themed surface; every accent comes from [chatPalette] so the
 * menu matches the bubbles and composer in both day and night.
 */
class MessageActionsSheet : AppCompatDialogFragment() {

    private val localKey: String get() = requireArguments().getString(ARG_LOCAL_KEY).orEmpty()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val binding = ChatSheetMessageActionsBinding.inflate(LayoutInflater.from(context))
        val palette = chatPalette(context)
        val density = resources.displayMetrics.density
        val hairline = maxOf(1, (0.5f * density).toInt())
        val args = requireArguments()

        binding.preview.background = GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(V3Palette.withAlpha(palette.primary, if (palette.isDark) 0.14f else 0.08f))
            setStroke(hairline, palette.alpha15)
        }
        binding.previewAccent.background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(palette.primary)
        }
        binding.tvSender.apply {
            val sender = args.getString(ARG_SENDER)
            isVisible = !sender.isNullOrBlank()
            text = sender
            typeface = context.v3Font(600)
            setTextColor(palette.textAccent)
        }
        binding.tvPreview.text = args.getString(ARG_CONTENT).orEmpty()

        // Reply is only offered for messages the server knows about
        // (Delivered + has a client_msg_id) — see ChatListViewModel.canReplyTo.
        binding.actionReply.root.isVisible = args.getBoolean(ARG_CAN_REPLY, true)

        val common = listOf(
            Action(binding.actionCopy, R.drawable.chat_ic_content_copy, R.string.chat_action_copy, ACTION_COPY),
            Action(binding.actionReply, R.drawable.chat_ic_reply, R.string.chat_action_reply, ACTION_REPLY),
            Action(binding.actionForward, R.drawable.chat_ic_forward, R.string.chat_action_forward, ACTION_FORWARD),
        ).filter { it.row.root.isVisible }
        common.forEachIndexed { index, action ->
            bindRow(action, palette, index, common.size, danger = false)
        }
        bindRow(
            Action(binding.actionDelete, R.drawable.chat_ic_delete, R.string.chat_action_delete, ACTION_DELETE),
            palette, 0, 1, danger = true,
        )

        return WitBottomSheet(context).apply { setSheetContent(binding.root) }
    }

    private class Action(
        val row: ChatItemMessageActionBinding,
        @DrawableRes val icon: Int,
        @StringRes val label: Int,
        val key: String,
    )

    /**
     * Connected segmented row (outer 20 / inner 5, 2dp gap) with a 17/17/17/7 icon tile.
     * Neutral rows put the theme colour only on the tile; delete uses the day/night danger
     * colour instead, independent of the user's theme.
     */
    private fun bindRow(action: Action, palette: V3Palette, index: Int, total: Int, danger: Boolean) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val row = action.row
        @ColorInt val accent =
            if (danger) ContextCompat.getColor(context, R.color.v3_danger) else palette.textAccent

        val outer = 20 * density
        val inner = 5 * density
        val top = if (index == 0) outer else inner
        val bottom = if (index == total - 1) outer else inner
        val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        val fill = GradientDrawable().apply {
            cornerRadii = radii
            setColor(palette.cardFill)
            setStroke(maxOf(1, (0.5f * density).toInt()), palette.cardHairline)
        }
        val mask = GradientDrawable().apply {
            cornerRadii = radii
            setColor(Color.WHITE)
        }
        row.root.background = RippleDrawable(
            ColorStateList.valueOf(V3Palette.withAlpha(accent, 0.16f)), fill, mask,
        )
        if (index > 0) {
            row.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = (2 * density).toInt() }
        }

        val tile = 17 * density
        row.iconTile.background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(tile, tile, tile, tile, tile, tile, 7 * density, 7 * density)
            setColor(ColorUtils.compositeColors(V3Palette.withAlpha(accent, 0.14f), palette.cardFill))
        }
        row.icon.setImageResource(action.icon)
        row.icon.imageTintList = ColorStateList.valueOf(accent)

        row.label.setText(action.label)
        row.label.typeface = context.v3Font(500)
        row.label.setTextColor(
            if (danger) accent else ContextCompat.getColor(context, R.color.v3_text_1),
        )
        row.root.setOnClickListener { finish(action.key) }
    }

    private fun finish(action: String) {
        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_ACTION to action, RESULT_LOCAL_KEY to localKey))
        dismiss()
    }

    companion object {
        const val TAG = "MessageActionsSheet"
        const val REQUEST_KEY = "MessageActionsSheet:result"
        const val RESULT_ACTION = "action"
        const val RESULT_LOCAL_KEY = "localKey"

        const val ACTION_COPY = "copy"
        const val ACTION_REPLY = "reply"
        const val ACTION_FORWARD = "forward"
        const val ACTION_DELETE = "delete"

        private const val ARG_LOCAL_KEY = "localKey"
        private const val ARG_CONTENT = "content"
        private const val ARG_SENDER = "sender"
        private const val ARG_CAN_REPLY = "canReply"

        fun newInstance(
            localKey: String,
            content: String?,
            sender: String? = null,
            canReply: Boolean = true,
        ): MessageActionsSheet =
            MessageActionsSheet().apply {
                arguments = bundleOf(
                    ARG_LOCAL_KEY to localKey,
                    ARG_CONTENT to content,
                    ARG_SENDER to sender,
                    ARG_CAN_REPLY to canReply,
                )
            }
    }
}
