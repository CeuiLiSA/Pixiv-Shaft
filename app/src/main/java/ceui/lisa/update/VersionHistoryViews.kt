package ceui.lisa.update

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.card
import ceui.pixiv.witstudio.theme.cardSurface
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.iconTile
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.setTextWithIcon
import ceui.pixiv.witstudio.theme.shape

/** 折叠时的更新说明行数；超过这个数才折叠，也才出「展开」按钮。 */
private const val COLLAPSED_LINES = 6

/**
 * 绑定时先猜这段说明会不会超过 [COLLAPSED_LINES] 行。
 *
 * 只是**初值**：真正的判据是量完之后 `Layout` 的最后一行有没有停在文本末尾之前
 * （见 [ReleaseCardView.onMeasure]）。猜一手是为了让绝大多数卡第一次量就对，
 * 不用在测量里改可见性再量第二遍。判据取原始 Markdown 的长度和行数，
 * GitHub 的 release body 一律是「标题 + 分节 + 若干条目」，这个初值基本都命中。
 */
private fun CharSequence.worthCollapsing(): Boolean =
    length > 200 || count { it == '\n' } >= COLLAPSED_LINES

/**
 * 顶部汇总卡（V3「管理」配方的紧凑汇总）：当前版本是这一页的主语，
 * 下面一行交代「现在是什么状态」，整页唯一的实色主操作就在它下面。
 *
 * 已是最新时不摆按钮 —— 没有下一步就不该有一个按不动的胶囊；Google Play 渠道
 * 换成一句「请通过商店更新」，因为这里的安装包对它无效。
 */
internal class VersionSummaryView(ctx: Context) : LinearLayout(ctx) {
    private val palette = V3Palette.from(ctx)
    private val version = ctx.label("", 26f, 700).apply {
        lineHeightRatio(1.2f)
        fontFeatureSettings = "tnum"
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val statusIcon = ImageView(ctx)
    private val statusText = ctx.label("", 14f, 400, ctx.color(R.color.v3_text_2)).apply {
        lineHeightRatio(1.6f)
    }
    private val action = ctx.pillButton("") {}

    init {
        orientation = VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(16) }
        background = ctx.card(22)
        setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20))

        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ctx.iconTile(R.drawable.ic_history_black_24dp), LayoutParams(ctx.dp(48), ctx.dp(48)))
            val column = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(
                    ctx.label(ctx.getString(R.string.update_current_version), 12f, 500, ctx.color(R.color.v3_text_2)),
                    LayoutParams(-1, -2),
                )
                addView(version, LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
            }
            addView(column, LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(14) })
        }
        addView(head, LayoutParams(-1, -2))

        addView(
            View(ctx).apply { setBackgroundColor(palette.cardHairline) },
            LayoutParams(-1, ctx.dp(1).coerceAtLeast(1)).apply { topMargin = ctx.dp(18) },
        )

        val status = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                statusIcon.apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                LayoutParams(ctx.dp(18), ctx.dp(18)),
            )
            addView(statusText, LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(10) })
        }
        addView(status, LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) })
        addView(action, LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) })
    }

    fun bind(item: VersionSummaryItem, onUpdate: (GitHubRelease) -> Unit) {
        version.text = item.currentVersion
        val update = item.update
        if (update != null) {
            val size = AppUpdateChecker.findApkAsset(update)?.size ?: 0L
            val found = context.getString(R.string.update_found_new, update.versionName)
            statusText.text = if (size > 0) "$found · ${formatApkSize(size)}" else found
            statusIcon.setImageResource(R.drawable.ic_file_download_black_24dp)
            statusIcon.imageTintList = ColorStateList.valueOf(palette.textAccent)
            // 整宽胶囊的图标必须行内放，compound drawable 会被钉在最左边。
            action.setTextWithIcon(
                context.getString(R.string.version_history_update_action, update.versionName),
                R.drawable.ic_file_download_black_24dp,
            )
            action.setOnClickListener { onUpdate(update) }
            action.isVisible = true
        } else {
            val lite = BuildConfig.IS_LITE
            statusText.setText(if (lite) R.string.update_channel_google else R.string.version_history_up_to_date)
            statusIcon.setImageResource(if (lite) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_check_24dp)
            statusIcon.imageTintList = ColorStateList.valueOf(
                if (lite) palette.textAccent else context.color(R.color.v3_green)
            )
            action.setOnClickListener(null)
            action.isVisible = false
        }
    }
}

/**
 * 一条发布记录：版本号 → 可选的一枚状态小标 → 日期 → 标题 → 更新说明 → 展开入口。
 *
 * 整张卡就是展开的点击区（48dp 以上），末端的文字加箭头说明它能展开；说明不超过
 * [COLLAPSED_LINES] 行时不出这个入口——按了什么都不会发生的链接比没有更糟。
 */
internal class ReleaseCardView(ctx: Context) : LinearLayout(ctx) {
    private val palette = V3Palette.from(ctx)
    private val versionTag = ctx.label("", 20f, 700).apply {
        lineHeightRatio(1.3f)
        fontFeatureSettings = "tnum"
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val badge = ctx.label("", 11f, 600).apply {
        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        letterSpacing = .04f
    }
    private val date = ctx.label("", 12f, 500, ctx.color(R.color.v3_text_2)).apply {
        fontFeatureSettings = "tnum"
    }
    private val title = ctx.label("", 16f, 600).apply { lineHeightRatio(1.5f) }
    private val body = ctx.label("", 14f, 400, ctx.color(R.color.v3_text_2)).apply {
        lineHeightRatio(1.7f)
        // 刻意不设 ellipsize：设了之后 Layout 会被截到 maxLines、末行并进剩余文字，
        // onMeasure 里就再也数不出「完整内容有多少行」（详见 [onMeasure]）。
        // 代码块底会往上下各多画 6dp，正文留出同量 padding，末尾那块不会被裁掉。
        setPadding(0, ctx.dp(6), 0, ctx.dp(6))
    }
    private val size = ctx.label("", 12f, 500, ctx.color(R.color.v3_text_2)).apply {
        fontFeatureSettings = "tnum"
    }

    /**
     * 展开 / 收起是这张卡上唯一的操作，做成一颗小号次级胶囊（浅底 + 描边 + 末端箭头）。
     * 先前只有一行小字加箭头，在满是 markdown 文字的卡里读不出是个按钮；
     * 而通用的 [compactPill] 有 48dp 最小高度，摆在卡角上又太壮。这里按 34dp 收窄——
     * 整张卡本身就是同一个动作的点击区，热区不靠这颗胶囊撑。
     */
    private val toggle = ctx.expandPill()
    private val footer = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(size, LayoutParams(0, -2, 1f))
        addView(toggle, LayoutParams(-2, -2).apply { marginStart = ctx.dp(12) })
    }

    /** 当前条目是不是展开态：展开时不必再核对截断。 */
    private var expanded = false

    // 绑定跑在滚动里，这几样每次重造纯属浪费：徽标底两种、箭头两个，构造时就位。
    private val currentBadgeBg = shape(999f, palette.alpha15)
    private val latestBadgeBg = shape(999f, ctx.color(R.color.v3_surface_2))
    private val arrowDown = ctx.expandArrow(R.drawable.ic_baseline_keyboard_arrow_down_24)
    private val arrowUp = ctx.expandArrow(R.drawable.ic_keyboard_arrow_up_black_24dp)

    init {
        orientation = VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(12) }
        background = ctx.cardSurface(22)
        setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(versionTag, LayoutParams(-2, -2))
            addView(badge, LayoutParams(-2, -2).apply { marginStart = ctx.dp(10) })
            addView(View(ctx), LayoutParams(0, 0, 1f))
            addView(date, LayoutParams(-2, -2).apply { marginStart = ctx.dp(10) })
        }
        addView(head, LayoutParams(-1, -2))
        addView(title, LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) })
        addView(body, LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        addView(footer, LayoutParams(-1, -2).apply { topMargin = ctx.dp(14) })
    }

    fun bind(item: ReleaseItem, notes: ChangelogRenderer, onToggle: (ReleaseItem) -> Unit) {
        val release = item.release
        versionTag.text = release.tagName

        badge.isVisible = item.isCurrent || item.isLatest
        if (item.isCurrent) {
            // 装着的那一版是用户在这一页最关心的一件事，给它整页仅有的一处主题浅底。
            badge.setText(R.string.update_current_version)
            badge.setTextColor(palette.textAccent)
            badge.background = currentBadgeBg
        } else if (item.isLatest) {
            badge.setText(R.string.update_latest_label)
            badge.setTextColor(context.color(R.color.v3_text_2))
            badge.background = latestBadgeBg
        }

        date.text = release.publishedAt?.takeIf { it.isNotBlank() }
            ?.let { Common.getLocalYYYYMMDDHHMMString(it).take(10) }.orEmpty()

        val name = release.name?.takeIf { it.isNotBlank() && it != release.tagName }
        title.isVisible = name != null
        title.text = name.orEmpty()

        val changelog = release.body?.takeIf { it.isNotBlank() }
        if (changelog != null) {
            notes.apply(body, release.tagName, changelog)
        } else {
            body.setText(R.string.update_no_changelog)
        }
        expanded = item.expanded
        body.maxLines = if (item.expanded) Int.MAX_VALUE else COLLAPSED_LINES
        toggle.isVisible = item.expanded || changelog?.worthCollapsing() == true

        val apk = AppUpdateChecker.findApkAsset(release)
        size.isVisible = apk != null
        size.text = apk?.let { formatApkSize(it.size) }.orEmpty()

        toggle.setText(
            if (item.expanded) R.string.version_history_collapse else R.string.version_history_expand
        )
        toggle.setCompoundDrawablesRelative(
            null, null, if (item.expanded) arrowUp else arrowDown, null,
        )
        toggle.setOnClickListener { onToggle(item) }
        // 整张卡是同一个动作的大点击区。刻意不给卡片设 contentDescription：
        // 那会把整张卡合并成一个读屏节点，更新说明就读不到了——动作的名字由胶囊的文案承担。
        setOnClickListener { onToggle(item) }
        // ⚠️ 必须排在 setOnClickListener 之后：那个方法会把 view 强行置成 clickable，
        // 写在它前面等于没写——没得展开的卡照样按出一圈涟漪，按了又什么都不发生。
        isClickable = toggle.isVisible
        isFocusable = toggle.isVisible
    }

    /**
     * 按钮显隐的最终判据：折叠态下正文是不是真超过了 [COLLAPSED_LINES] 行。
     *
     * 放在测量里核对，而不是 `post` 到下一帧——后者会让卡片在滚动中先矮一下再长高。
     * 判据就是 `TextView.getLineCount()`：`maxLines` 只在 `ellipsize != null` 时才会把
     * `Layout` 本身截短，没有 ellipsize 时 Layout 仍然排完整篇、只是不画超出的行，
     * 于是这个行数就是「完整内容有多少行」（实测 6 行的卡 `lineCount` 是 14）。
     * 反过来，`getEllipsisCount` / `getLineEnd` 两个判据都靠不住：设了 ellipsize 之后
     * 末行会把剩下的文字并进来，`getLineEnd(最后一行)` 恒等于文本长度。
     * [bind] 已按文本长度猜过一手，这里通常什么都不用改；猜错了才改一次可见性再量一遍。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (expanded) return
        if (body.layout == null) return
        val truncated = body.lineCount > COLLAPSED_LINES
        if (truncated != toggle.isVisible) {
            toggle.isVisible = truncated
            isClickable = truncated
            isFocusable = truncated
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

/** 胶囊末端的箭头：16dp、主题强调色，随卡片构造一次。 */
private fun Context.expandArrow(@DrawableRes icon: Int): Drawable? =
    ContextCompat.getDrawable(this, icon)?.mutate()?.apply {
        setTint(V3Palette.from(this@expandArrow).textAccent)
        setBounds(0, 0, dp(16), dp(16))
    }

/** 小号次级胶囊：12sp 文字 + 末端箭头，34dp 高，只用在卡角上。 */
private fun Context.expandPill(): TextView {
    val p = V3Palette.from(this)
    return label("", 12f, 600, p.textAccent).apply {
        gravity = Gravity.CENTER
        minHeight = dp(34)
        setPadding(dp(14), dp(7), dp(10), dp(7))
        background = ripple(p.pillSecondary(999f, hairlinePx()), shape(999f, Color.WHITE))
        compoundDrawablePadding = dp(4)
        isClickable = true
        isFocusable = true
        pressScale()
    }
}

/** feeds 用的手写 ViewBinding：这两张卡都是代码搭的，没有 XML 可以 inflate。 */
internal class VersionSummaryBinding(private val view: VersionSummaryView) : ViewBinding {
    override fun getRoot(): VersionSummaryView = view
}

internal class ReleaseCardBinding(private val view: ReleaseCardView) : ViewBinding {
    override fun getRoot(): ReleaseCardView = view
}
