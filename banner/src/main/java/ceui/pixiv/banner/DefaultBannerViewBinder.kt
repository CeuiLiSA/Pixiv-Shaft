package ceui.pixiv.banner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * Built-in binder for [BannerRequest.Text]. Uses `androidx.cardview.widget.CardView`
 * + native `Button` (auto-upgrades to AppCompatButton) so the binder works
 * under the project's AppCompat-based theme without triggering Material's
 * theme-enforcement crashes.
 *
 * Only [BannerIcon.Resource] is rendered — URL icons would need to be loaded
 * via this binder's caller (e.g. a custom binder using Glide).
 */
class DefaultBannerViewBinder : BannerViewBinder {

    override val key: String = BannerViewBinder.DEFAULT_KEY

    override fun create(parent: ViewGroup): View =
        LayoutInflater.from(parent.context).inflate(R.layout.banner_default, parent, false)

    override fun bind(view: View, request: BannerRequest, callbacks: BannerCallbacks) {
        val text = request as? BannerRequest.Text ?: error(
            "DefaultBannerViewBinder only handles BannerRequest.Text; got ${request::class.simpleName}. " +
                "Register a custom binder under request.binderKey for non-text banners.",
        )

        val icon = view.findViewById<ImageView>(R.id.banner_icon)
        val title = view.findViewById<TextView>(R.id.banner_title)
        val message = view.findViewById<TextView>(R.id.banner_message)
        val action = view.findViewById<Button>(R.id.banner_action)

        title.text = text.title

        if (text.message.isNullOrBlank()) {
            message.isVisible = false
        } else {
            message.isVisible = true
            message.text = text.message
        }

        when (val src = text.icon) {
            is BannerIcon.Resource -> {
                icon.isVisible = true
                icon.setImageResource(src.resId)
            }
            is BannerIcon.Url, null -> {
                icon.isVisible = false
                icon.setImageDrawable(null)
            }
        }

        val actionData = text.action
        if (actionData == null) {
            action.isVisible = false
            action.setOnClickListener(null)
        } else {
            action.isVisible = true
            action.text = actionData.label
            action.setOnClickListener {
                callbacks.triggerAction(actionData.actionKey)
                callbacks.dismiss(BannerDismissReason.UserTap)
            }
        }

        view.setOnClickListener {
            callbacks.triggerTap()
            callbacks.dismiss(BannerDismissReason.UserTap)
        }
    }
}
