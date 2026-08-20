package ceui.pixiv.chat.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import ceui.lisa.R
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.widget.BaseBottomSheetDialogFragment
import ceui.lisa.databinding.ChatSheetMessageActionsBinding

/**
 * Bottom sheet presenting contextual actions for a chat message:
 * copy, reply, forward, delete.
 *
 * Communicates the chosen action back to the host fragment via
 * the Fragment Result API ([REQUEST_KEY] / [RESULT_ACTION]).
 */
class MessageActionsSheet : BaseBottomSheetDialogFragment(R.layout.chat_sheet_message_actions) {

    private val binding by viewBinding(ChatSheetMessageActionsBinding::bind)

    private val localKey: String get() = requireArguments().getString(ARG_LOCAL_KEY).orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPreview.text = arguments?.getString(ARG_CONTENT).orEmpty()

        // Reply is only offered for messages the server knows about
        // (Delivered + has a client_msg_id) — see ChatListViewModel.canReplyTo.
        binding.actionReply.isVisible = arguments?.getBoolean(ARG_CAN_REPLY, true) ?: true

        binding.actionCopy.setOnClickListener { finish(ACTION_COPY) }
        binding.actionReply.setOnClickListener { finish(ACTION_REPLY) }
        binding.actionForward.setOnClickListener { finish(ACTION_FORWARD) }
        binding.actionDelete.setOnClickListener { finish(ACTION_DELETE) }
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
        private const val ARG_CAN_REPLY = "canReply"

        fun newInstance(localKey: String, content: String?, canReply: Boolean = true): MessageActionsSheet =
            MessageActionsSheet().apply {
                arguments = bundleOf(
                    ARG_LOCAL_KEY to localKey,
                    ARG_CONTENT to content,
                    ARG_CAN_REPLY to canReply,
                )
            }
    }
}
