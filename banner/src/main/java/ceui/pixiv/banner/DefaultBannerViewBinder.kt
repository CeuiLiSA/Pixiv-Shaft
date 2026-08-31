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
 * Layout (see `banner_default.xml`): a 44dp circular icon slot, then
 * caption / title / message stacked, then either a chevron (when the request
 * carries a [BannerRequest.deepLink] and no explicit action) or the action
 * button.
 *
 * [BannerIcon.Resource] is rendered directly; [BannerIcon.Url] goes through the
 * host-supplied [iconLoader] and is hidden when no loader was provided.
 */
class DefaultBannerViewBinder(
    private val iconLoader: BannerIconLoader? = null,
) : BannerViewBinder {

    override val key: String = BannerViewBinder.DEFAULT_KEY

    override fun create(parent: ViewGroup): View =
        LayoutInflater.from(parent.context).inflate(R.layout.banner_default, parent, false)

    override fun bind(view: View, request: BannerRequest, callbacks: BannerCallbacks) {
        val text = request as? BannerRequest.Text ?: error(
            "DefaultBannerViewBinder only handles BannerRequest.Text; got ${request::class.simpleName}. " +
                "Register a custom binder under request.binderKey for non-text banners.",
        )

        val iconFrame = view.findViewById<View>(R.id.banner_icon_frame)
        val icon = view.findViewById<ImageView>(R.id.banner_icon)
        val caption = view.findViewById<TextView>(R.id.banner_caption)
        val title = view.findViewById<TextView>(R.id.banner_title)
        val message = view.findViewById<TextView>(R.id.banner_message)
        val chevron = view.findViewById<View>(R.id.banner_chevron)
        val action = view.findViewById<Button>(R.id.banner_action)

        bindText(caption, text.caption)
        title.text = text.title
        bindText(message, text.message)

        when (val src = text.icon) {
            is BannerIcon.Resource -> {
                iconFrame.isVisible = true
                icon.setImageResource(src.resId)
            }
            is BannerIcon.Url -> {
                val loader = iconLoader
                if (loader == null) {
                    iconFrame.isVisible = false
                    icon.setImageDrawable(null)
                } else {
                    iconFrame.isVisible = true
                    loader.load(icon, src.url)
                }
            }
            null -> {
                iconFrame.isVisible = false
                icon.setImageDrawable(null)
            }
        }

        val actionData = text.action
        if (actionData == null) {
            action.isVisible = false
            action.setOnClickListener(null)
            chevron.isVisible = text.deepLink != null
        } else {
            chevron.isVisible = false
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

    private fun bindText(target: TextView, value: String?) {
        if (value.isNullOrBlank()) {
            target.isVisible = false
            target.text = null
        } else {
            target.isVisible = true
            target.text = value
        }
    }
}
