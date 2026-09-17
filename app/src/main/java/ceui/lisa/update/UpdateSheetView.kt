package ceui.lisa.update

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.card
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.iconTile
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.sectionLabel
import ceui.pixiv.witstudio.theme.shape
import io.noties.markwon.Markwon

/**
 * 更新弹窗的内容，V3「弹窗」配方：标题 → 当前状态（版本对比）→ 更新日志 → 进度 → 主操作 →
 * 两个低优先级文字入口。一个区域只有一个最强操作，所以「下载更新」是唯一的实色胶囊，
 * 「稍后」「跳过此版本」都是文字。
 *
 * 视图只管长相和状态切换；下载、校验与安装全在 [UpdateBottomSheet]。
 */
internal class UpdateSheetView(ctx: Context) : LinearLayout(ctx) {

    private val palette = V3Palette.from(ctx)

    private val versions = ctx.label("", 14f, 500, ctx.color(R.color.v3_text_2)).apply {
        lineHeightRatio(1.5f)
        fontFeatureSettings = "tnum"
    }
    private val changelog = ctx.label("", 14f, 400, ctx.color(R.color.v3_text_2)).apply {
        lineHeightRatio(1.7f)
        // 同版本历史：给代码块底的上下溢出留位置。
        setPadding(0, ctx.dp(6), 0, ctx.dp(6))
    }
    private val assetInfo = ctx.label("", 12f, 500, ctx.color(R.color.v3_text_2)).apply {
        fontFeatureSettings = "tnum"
    }
    private val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        // 胶囊端的 2dp 圆角轨道，同「正在下载」列表；颜色走 tint，与主题色解耦。
        progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.v3_progress_horizontal)
        progressTintList = ColorStateList.valueOf(palette.primary)
        progressBackgroundTintList = ColorStateList.valueOf(ctx.color(R.color.v3_progress_track))
    }
    private val progressText = ctx.label("", 12f, 500, ctx.color(R.color.v3_text_2)).apply {
        gravity = Gravity.END
        fontFeatureSettings = "tnum"
    }
    private val progressGroup = LinearLayout(ctx).apply {
        orientation = VERTICAL
        isVisible = false
        addView(progressBar, LayoutParams(-1, ctx.dp(4)))
        addView(progressText, LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
    }

    /** 唯一的实色主操作：下载 → 下载中 → 安装 / 重试，文案与点击都由宿主换。 */
    val primary: TextView = ctx.pillButton("", icon = R.drawable.ic_file_download_black_24dp) {}

    /** 低优先级入口：只有文字，没有底色，不跟主操作抢。 */
    val later: TextView = ctx.textAction(ctx.getString(R.string.update_later), palette.textAccent)
    val skip: TextView = ctx.textAction(
        ctx.getString(R.string.update_skip_version),
        ctx.color(R.color.v3_text_2),
    )

    init {
        orientation = VERTICAL
        setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), ctx.dp(12))

        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ctx.iconTile(R.drawable.ic_file_download_black_24dp),
                LayoutParams(ctx.dp(48), ctx.dp(48)),
            )
            val column = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(
                    ctx.label(ctx.getString(R.string.update_new_version_available), 20f, 700).apply {
                        lineHeightRatio(1.35f)
                        ViewCompat.setAccessibilityHeading(this, true)
                    },
                    LayoutParams(-1, -2),
                )
                addView(versions, LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
            }
            addView(column, LayoutParams(0, -2, 1f).apply { marginStart = ctx.dp(14) })
        }
        addView(head, LayoutParams(-1, -2))

        // 更新日志坐在一张 22dp 卡里：它是弹窗里唯一的长文本区，需要一个自己的边界。
        val logCard = LinearLayout(ctx).apply {
            orientation = VERTICAL
            background = ctx.card(22)
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            // eyebrow 用 v3_text_2 而不是 sectionLabel 默认的 v3_text_3：后者只有 33% alpha，
            // 压在 cardFill 上实测 2.05:1，「更新日志」是承载信息的小标，不是纯装饰。
            addView(
                ctx.sectionLabel(ctx.getString(R.string.update_changelog_title)).apply {
                    setTextColor(ctx.color(R.color.v3_text_2))
                },
                LayoutParams(-1, -2),
            )
            // 上限同时看屏幕：横屏(411dp 高)下固定 280dp 会把主操作挤出屏幕外，而 sheet 已经
            // 展开到顶、根布局又不可滚，用户就点不到「下载更新」了。
            val logMaxHeight = minOf(ctx.dp(280), (ctx.resources.displayMetrics.heightPixels * .3f).toInt())
            val scroll = BoundedScrollView(ctx, logMaxHeight).apply {
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(ctx.dp(16))
                addView(changelog, LayoutParams(-1, -2))
            }
            addView(scroll, LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        }
        addView(logCard, LayoutParams(-1, -2).apply { topMargin = ctx.dp(20) })

        addView(assetInfo, LayoutParams(-1, -2).apply {
            topMargin = ctx.dp(12)
            marginStart = ctx.dp(4)
        })
        addView(progressGroup, LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) })
        addView(primary, LayoutParams(-1, -2).apply { topMargin = ctx.dp(18) })

        val secondary = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            addView(later, LayoutParams(0, -2, 1f))
            addView(skip, LayoutParams(0, -2, 1f))
        }
        addView(secondary, LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
    }

    fun bind(currentVersion: String, release: GitHubRelease, markwon: Markwon) {
        versions.text = context.getString(
            R.string.update_version_format,
            currentVersion,
            release.versionName,
        )
        val body = release.body?.takeIf { it.isNotBlank() }
        if (body != null) {
            markwon.setMarkdown(changelog, body)
        } else {
            changelog.setText(R.string.update_no_changelog)
        }
        val apk = AppUpdateChecker.findApkAsset(release)
        assetInfo.isVisible = apk != null
        assetInfo.text = apk?.let { "${it.name} · ${formatApkSize(it.size)}" }.orEmpty()
    }

    /** 主操作的文案、图标与可用性；下载中禁止重复点。 */
    fun setPrimary(
        @StringRes text: Int,
        enabled: Boolean,
        @DrawableRes icon: Int = R.drawable.ic_file_download_black_24dp,
        onClick: (() -> Unit)? = null,
    ) {
        primary.setText(text)
        primary.isEnabled = enabled
        primary.alpha = if (enabled) 1f else .6f
        val glyph = ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
            setTint(palette.onPrimary)
            setBounds(0, 0, context.dp(18), context.dp(18))
        }
        primary.setCompoundDrawablesRelative(glyph, null, null, null)
        primary.setOnClickListener(onClick?.let { action -> OnClickListener { action() } })
    }

    /** 进度区一旦出现就不再收起：收起会让整个弹窗跳一下高度。 */
    fun showProgress() {
        progressGroup.isVisible = true
        // 下载已经开始，跳过这一版就不再是有意义的下一步了。
        skip.isVisible = false
    }

    fun setProgress(percent: Int, label: CharSequence) {
        progressBar.progress = percent
        progressText.text = label
    }

    fun setProgressMessage(@StringRes text: Int) {
        progressText.setText(text)
    }

    fun resetProgress() {
        progressBar.progress = 0
    }
}

/** 文字入口：没有底色，只有涟漪和 48dp 热区。 */
private fun Context.textAction(text: CharSequence, textColor: Int): TextView =
    label(text, 14f, 600, textColor).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ripple(null, shape(999f, Color.WHITE))
        isClickable = true
        isFocusable = true
        pressScale()
    }

/**
 * 高度有上限的滚动容器：更新日志可以很长，但弹窗不能因此顶到屏幕顶端。
 *
 * [NestedScrollView] 不认 `maxHeight`（旧布局里那行 `android:maxHeight` 一直是死的），
 * 只能在测量时把高度约束换成 AT_MOST。
 */
private class BoundedScrollView(ctx: Context, private val maxHeightPx: Int) : NestedScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST),
        )
    }
}
