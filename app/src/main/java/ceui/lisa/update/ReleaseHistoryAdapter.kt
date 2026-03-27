package ceui.lisa.update

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.ItemReleaseTimelineBinding
import ceui.lisa.utils.Common
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme

class ReleaseHistoryAdapter(
    targetList: List<GitHubRelease>,
    context: Context
) : BaseAdapter<GitHubRelease, ItemReleaseTimelineBinding>(targetList, context) {

    private val markwon = Markwon.builder(context)
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.headingTextSizeMultipliers(floatArrayOf(1.2f, 1.1f, 1.05f, 1f, 0.9f, 0.85f))
                builder.linkColor(ContextCompat.getColor(context, R.color.user_name_horizontal))
            }
        })
        .build()
    private val currentVersion = BuildConfig.VERSION_NAME

    override fun initLayout() {
        mLayoutID = R.layout.item_release_timeline
    }

    override fun bindData(
        target: GitHubRelease,
        bindView: ViewHolder<ItemReleaseTimelineBinding>,
        position: Int
    ) {
        val b = bindView.baseBind
        val version = target.tagName.removePrefix("v").removePrefix("V")

        // Timeline: hide top line for first item, hide bottom line for last
        b.lineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        b.lineBottom.visibility = if (position == allItems.size - 1) View.INVISIBLE else View.VISIBLE

        // Highlight current/latest
        val isCurrent = version == currentVersion
        val isLatest = position == 0

        b.dot.setBackgroundResource(
            if (isCurrent || isLatest) R.drawable.timeline_dot_active
            else R.drawable.timeline_dot
        )

        if (isLatest) {
            b.lineTop.setBackgroundColor(0x00000000)
        }

        // Version badge
        b.versionBadge.text = target.tagName
        b.versionBadge.setBackgroundResource(
            if (isCurrent || isLatest) R.drawable.badge_version
            else R.drawable.badge_version_grey
        )

        // Labels
        b.labelCurrent.visibility = if (isCurrent) View.VISIBLE else View.GONE
        b.labelLatest.visibility = if (isLatest && !isCurrent) View.VISIBLE else View.GONE

        // Date
        val date = target.publishedAt
        b.releaseDate.text = if (!date.isNullOrBlank()) {
            Common.getLocalYYYYMMDDHHMMString(date)
        } else {
            ""
        }

        // Title
        val title = target.name
        b.releaseTitle.text = if (!title.isNullOrBlank()) title else target.tagName

        // Body (Markdown)
        val body = target.body
        if (!body.isNullOrBlank()) {
            markwon.setMarkdown(b.releaseBody, body)
            b.releaseBody.visibility = View.VISIBLE
        } else {
            b.releaseBody.visibility = View.GONE
        }

        // APK asset info
        val apk = AppUpdateChecker.findApkAsset(target)
        if (apk != null) {
            val sizeMB = apk.size / 1048576f
            b.assetInfo.text = String.format("%s (%.1f MB)", apk.name, sizeMB)
            b.assetRow.visibility = View.VISIBLE
        } else {
            b.assetRow.visibility = View.GONE
        }

        // Click to expand/collapse body
        b.releaseCard.setOnClickListener {
            val isCollapsed = b.releaseBody.maxLines == 6
            b.releaseBody.maxLines = if (isCollapsed) Int.MAX_VALUE else 6
        }
    }
}
