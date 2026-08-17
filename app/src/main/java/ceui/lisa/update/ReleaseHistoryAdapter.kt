package ceui.lisa.update

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.ItemReleaseTimelineBinding
import ceui.lisa.fragments.SettingsCatalog
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.theme.V3Palette
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
    private val palette = V3Palette.from(context)

    // 非当前版本徽章的 tonal 底：卡底再混一截主题色（同设置主页 icon 圆底）
    private val tonalBadge = androidx.core.graphics.ColorUtils.blendARGB(
        palette.cardFill, palette.primary, if (palette.isDark) 0.16f else 0.14f
    )
    private val text2 = ContextCompat.getColor(context, R.color.v3_text_2)
    private val text3 = ContextCompat.getColor(context, R.color.v3_text_3)

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
        // 复用时恢复颜色（曾绑定过最新条目的 view 顶线会被清成透明）
        b.lineTop.setBackgroundColor(if (isLatest) 0x00000000 else text3)

        // Version badge：当前/最新用主题正色，其余 tonal 底
        val active = isCurrent || isLatest
        b.versionBadge.text = target.tagName
        b.versionBadge.setBackgroundResource(
            if (active) R.drawable.badge_version else R.drawable.badge_version_grey
        )
        (b.versionBadge.background.mutate() as? android.graphics.drawable.GradientDrawable)
            ?.setColor(if (active) palette.primary else tonalBadge)
        b.versionBadge.setTextColor(if (active) 0xFFFFFFFF.toInt() else text2)

        // 卡片底色/描边跟随主题
        SettingsCatalog.applyThemedRowBg(b.releaseCard)

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
