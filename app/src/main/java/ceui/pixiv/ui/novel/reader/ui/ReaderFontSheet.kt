package ceui.pixiv.ui.novel.reader.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.ConfigurationCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.pixiv.services.appServices
import ceui.pixiv.ui.novel.reader.settings.ReaderFontRepository
import ceui.pixiv.ui.novel.reader.settings.ReaderFontRepository.State
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderWebFont
import ceui.pixiv.ui.novel.reader.settings.ReaderWebFont.Group
import ceui.pixiv.ui.settings.GithubProxyDialog
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import ceui.pixiv.witstudio.theme.IconButton
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.compactPill
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.rowShape
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 小说阅读器「更多字体」（#1060）：按简体 / 繁体 / 日文分段列出 [ReaderWebFont]，就地下载、选用、删除。
 *
 * 选项的差别是字形，所以每行都带预览（字体自己的轮廓，未下载也能看）。下载状态来自进程级
 * [ReaderFontRepository]：面板关了下载继续，再打开直接接上进度。
 * 选用已下载的字体会写 [ReaderSettings.fontId] 并收起；[onDismiss] 让阅读设置面板刷新字体那一行。
 */
object ReaderFontSheet {

    fun show(context: Context, onDismiss: () -> Unit): Dialog {
        val repo = context.appServices().readerFontRepository
        val sheet = WitBottomSheet(context)
        val rows = mutableListOf<FontRow>()
        val groups = ReaderWebFont.entries.filter { it.isSupported }.groupBy { it.group }
        val scroll = NestedScrollView(context)
        val groupBar = GroupBar(context, groups.keys.toList(), initialGroup(context, groups.keys)) { group ->
            rows.forEach { it.isVisible = it.font.group == group }
            scroll.scrollTo(0, 0)
        }

        // 标题与分段条钉在顶上，只有字体列表滚动：日文一组近二十款，滚到底也能直接换组
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(4), context.dp(20), context.dp(10))
            addView(context.label(context.getString(R.string.reader_font_more), 20f, 700).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(
                context.label(
                    context.getString(R.string.reader_font_sheet_desc),
                    13f,
                    400,
                    context.color(ceui.pixiv.witstudio.R.color.wit_text_2),
                ).apply { lineHeightRatio(1.45f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) },
            )
            addView(groupBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(18) })
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), 0, context.dp(20), context.dp(20))
            groups.forEach { (_, fonts) ->
                fonts.forEachIndexed { index, font ->
                    val row = FontRow(context, font, index, fonts.size, repo) { picked ->
                        if (ReaderSettings.fontId != picked.id) ReaderSettings.fontId = picked.id
                        rows.forEach { it.render(repo.states.value) }
                        // 选中态先落一拍再收起，让人看到选的是哪一款
                        if (motionEnabled()) sheet.window?.decorView?.postDelayed({ sheet.dismiss() }, 180)
                        else sheet.dismiss()
                    }
                    row.isVisible = font.group == groupBar.selected
                    rows += row
                    addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                        if (index > 0) topMargin = context.dp(2)
                    })
                }
            }
            addView(
                context.label(
                    context.getString(R.string.reader_font_github_hint),
                    12f,
                    400,
                    context.color(ceui.pixiv.witstudio.R.color.wit_text_2),
                ).apply { lineHeightRatio(1.6f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(20) },
            )
            // 低优先级入口：文字按钮，热区 48dp
            addView(
                context.label(
                    context.getString(R.string.reader_font_github_proxy),
                    14f,
                    600,
                    V3Palette.from(context).textAccent,
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = context.dp(48)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { GithubProxyDialog.show(context) {} }
                },
                LinearLayout.LayoutParams(-2, -2),
            )
        }

        scroll.addView(content)
        sheet.setSheetContent(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(-1, -2))
        })
        sheet.setOnDismissListener { onDismiss() }
        sheet.lifecycleScope.launch {
            repo.states.collect { states -> rows.forEach { it.render(states) } }
        }
        sheet.show()
        return sheet
    }

    /** 正在用下载字体就落在它那一组；否则按界面语言猜读者读哪种文字，其余语言默认日文（pixiv 小说以日文为主）。 */
    private fun initialGroup(context: Context, available: Set<Group>): Group {
        ReaderWebFont.byId(ReaderSettings.fontId)?.group?.takeIf { it in available }?.let { return it }
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
        val guess = when (locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) {
                Group.TRADITIONAL_CHINESE
            } else {
                Group.SIMPLIFIED_CHINESE
            }
            else -> Group.JAPANESE
        }
        return guess.takeIf { it in available } ?: available.first()
    }

    /**
     * 简体 / 繁体 / 日文单选分段：复用阅读设置的分段轨道与选中胶囊（42dp 轨道、36dp 选中块、48dp 热区）。
     * 选项按文案收紧并可横向滚动，长译文和大字体不会被截断。
     */
    private class GroupBar(
        context: Context,
        private val groups: List<Group>,
        initial: Group,
        private val onSelected: (Group) -> Unit,
    ) : HorizontalScrollView(context) {

        var selected: Group = initial
            private set
        private val cells = ArrayList<TextView>(groups.size)

        init {
            isHorizontalScrollBarEnabled = false
            val track = LinearLayout(context).apply {
                background = InsetDrawable(
                    ContextCompat.getDrawable(context, R.drawable.bg_reader_segment_track),
                    0, context.dp(3), 0, context.dp(3),
                )
                setPadding(context.dp(3), 0, context.dp(3), 0)
            }
            val textColors = ContextCompat.getColorStateList(context, R.color.reader_segment_text)
            groups.forEach { group ->
                val cell = context.label(context.getString(group.titleRes), 13f, 500).apply {
                    setTextColor(textColors)
                    gravity = Gravity.CENTER
                    setSingleLine()
                    minWidth = context.dp(64)
                    minimumHeight = context.dp(48)
                    setPadding(context.dp(16), 0, context.dp(16), 0)
                    background = InsetDrawable(
                        ContextCompat.getDrawable(context, R.drawable.bg_reader_segment_option),
                        0, context.dp(6), 0, context.dp(6),
                    )
                    isSelected = group == selected
                    setOnClickListener { select(group) }
                    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = RadioButton::class.java.name
                            info.isCheckable = true
                            info.isChecked = host.isSelected
                        }
                    })
                }
                cells += cell
                track.addView(cell, LinearLayout.LayoutParams(-2, -2))
            }
            addView(track, LayoutParams(-2, -2))
        }

        private fun select(group: Group) {
            if (group == selected) return
            selected = group
            cells.forEachIndexed { i, cell -> cell.isSelected = groups[i] == group }
            onSelected(group)
        }
    }

    /**
     * 一款字体一行：名称 → 预览 → 状态 / 大小（→ 下载进度条），末端是当前状态下唯一的动作。
     * 连通分段行（外角 20 内角 5），选用中的那行换主题浅底并在末端打勾。
     */
    private class FontRow(
        context: Context,
        val font: ReaderWebFont,
        private val index: Int,
        private val total: Int,
        private val repo: ReaderFontRepository,
        private val onPick: (ReaderWebFont) -> Unit,
    ) : LinearLayout(context) {

        private val palette = V3Palette.from(context)
        private val name = context.getString(font.nameRes)
        private val size = String.format(Locale.US, "%.1f MB", font.byteSize / 1_000_000f)
        private val textSecondary = context.color(ceui.pixiv.witstudio.R.color.wit_text_2)
        private val danger = context.color(R.color.v3_danger)

        private val meta = context.label("", 12f, 500, textSecondary)
        private val progress = LinearProgressIndicator(context).apply {
            isIndeterminate = false
            max = 100
            trackThickness = context.dp(4)
            trackCornerRadius = context.dp(2)
            setIndicatorColor(palette.primary)
            trackColor = palette.alpha15
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val action = FrameLayout(context)
        private var selected = false
        /** 进度每涨 1% 都会 render 一次；动作只在种类变化时重建，不然读屏焦点和按压态每跳一下就丢。 */
        private var actionKind: Any? = null
        private var renderedSelected: Boolean? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(72)
            setPadding(context.dp(16), context.dp(14), context.dp(8), context.dp(14))
            isClickable = true
            isFocusable = true

            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(context.label(name, 16f, 600))
                addView(
                    ImageView(context).apply {
                        setImageResource(font.previewRes)
                        scaleType = ImageView.ScaleType.FIT_START
                        imageTintList = ColorStateList.valueOf(context.color(ceui.pixiv.witstudio.R.color.wit_text_1))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LayoutParams(-1, context.dp(20)).apply { topMargin = context.dp(10) },
                )
                addView(meta, LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
                addView(progress, LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
            }
            addView(column, LayoutParams(0, -2, 1f))
            addView(action, LayoutParams(-2, -2).apply { marginStart = context.dp(8) })

            setOnClickListener {
                when (repo.states.value[font]) {
                    State.Installed -> onPick(font)
                    State.Absent, State.Failed, null -> repo.download(font)
                    is State.Downloading -> Unit
                }
            }
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = selected
                }
            })
        }

        fun render(states: Map<ReaderWebFont, State>) {
            val state = states[font] ?: State.Absent
            selected = state == State.Installed && ReaderSettings.fontId == font.id

            if (renderedSelected != selected) {
                renderedSelected = selected
                val fill = if (selected) ColorUtils.compositeColors(palette.alpha10, palette.cardFill) else null
                background = context.ripple(context.rowShape(index, total, fill), context.rowShape(index, total, Color.WHITE))
            }

            meta.setTextColor(if (state == State.Failed) danger else textSecondary)
            meta.text = when (state) {
                State.Absent -> context.getString(R.string.reader_font_state_absent, size)
                State.Installed -> context.getString(
                    if (selected) R.string.reader_font_state_in_use else R.string.reader_font_state_installed,
                    size,
                )
                is State.Downloading ->
                    context.getString(R.string.reader_font_state_downloading, (state.fraction * 100).toInt())
                State.Failed -> context.getString(R.string.reader_font_state_failed)
            }
            progress.isVisible = state is State.Downloading
            if (state is State.Downloading) progress.setProgressCompat((state.fraction * 100).toInt(), true)

            val kind: Any = when {
                selected -> "selected"
                state is State.Downloading -> State.Downloading::class
                else -> state
            }
            if (kind != actionKind) {
                actionKind = kind
                action.removeAllViews()
                action.addView(actionView(state), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            contentDescription = "$name, ${meta.text}"
        }

        private fun actionView(state: State): View = when {
            selected -> checkBadge()
            state == State.Installed -> IconButton(
                context,
                R.drawable.ic_delete_black_24dp,
                context.getString(R.string.reader_font_delete),
                danger,
            ).apply { setOnClickListener { repo.delete(font) } }
            state is State.Downloading -> IconButton(
                context,
                R.drawable.ic_close_black_24dp,
                context.getString(R.string.reader_font_cancel_download),
                palette.textAccent,
            ).apply { setOnClickListener { repo.cancel(font) } }
            else -> context.compactPill(
                context.getString(if (state == State.Failed) R.string.retry else R.string.reader_font_download),
            ) { repo.download(font) }.apply {
                // 行首的字体名已经读过，按钮补上对象，读屏不至于只听到一串「下载」
                contentDescription = "$text $name"
            }
        }

        /** 选用中：48dp 位里一枚 26dp 主题实色圆底 + onPrimary 勾，与插画布局选择器同一枚。 */
        private fun checkBadge(): View = FrameLayout(context).apply {
            minimumWidth = context.dp(48)
            minimumHeight = context.dp(48)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_check_24dp)
                    imageTintList = ColorStateList.valueOf(palette.onPrimary)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(palette.primary)
                    }
                    val pad = context.dp(5)
                    setPadding(pad, pad, pad, pad)
                },
                FrameLayout.LayoutParams(context.dp(26), context.dp(26), Gravity.CENTER),
            )
        }
    }
}
