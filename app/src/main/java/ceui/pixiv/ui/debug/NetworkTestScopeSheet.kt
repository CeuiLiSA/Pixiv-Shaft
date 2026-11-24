package ceui.pixiv.ui.debug

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.pressScale

/**
 * 网络测试范围定制 Sheet（长按「开始测试」呼出）。
 * 胶囊 + 内嵌文本形态，提供模块多选与全选/反选快捷切换。
 * 不做持久化存储，在 ViewModel 中保持状态（退出页面后重置）。
 */
object NetworkTestScopeSheet {

    fun interface OnScopePicked {
        fun onScopePicked(modules: Set<NetworkTestModule>)
    }

    private data class ScopeItem(
        val module: NetworkTestModule,
        val titleRes: Int,
        val descRes: Int,
    )

    private val ITEMS = listOf(
        ScopeItem(
            NetworkTestModule.APP_API,
            R.string.network_test_scope_app_api_title,
            R.string.network_test_scope_app_api_desc,
        ),
        ScopeItem(
            NetworkTestModule.WEB_API,
            R.string.network_test_scope_web_api_title,
            R.string.network_test_scope_web_api_desc,
        ),
        ScopeItem(
            NetworkTestModule.IMAGE,
            R.string.network_test_scope_image_title,
            R.string.network_test_scope_image_desc,
        ),
        ScopeItem(
            NetworkTestModule.PIXSHAFT,
            R.string.network_test_scope_pixshaft_title,
            R.string.network_test_scope_pixshaft_desc,
        ),
    )

    @JvmStatic
    fun show(
        context: Context,
        currentSelection: Set<NetworkTestModule>,
        onScopePicked: OnScopePicked,
    ): WitBottomSheet {
        val sheet = WitBottomSheet(context)
        val selected = currentSelection.toMutableSet()
        var confirmed = false
        val palette = V3Palette.from(context)
        val surface = context.color(R.color.v3_bg)
        val idleFill = ColorUtils.compositeColors(context.color(R.color.v3_surface_3), surface)
        val selectedFill = ColorUtils.compositeColors(palette.alpha20, surface)
        val checkBoxRadius = context.dpF(6f)
        val checkBoxStrokePx = context.dpF(1.5f).toInt().coerceAtLeast(2)
        val checkBoxPad = context.dp(3)
        val uncheckedStrokeColor = context.color(R.color.v3_text_2)

        val cardViews = mutableListOf<Triple<NetworkTestModule, LinearLayout, ImageView>>()

        fun renderAll() {
            cardViews.forEach { (module, card, icon) ->
                val isSel = selected.contains(module)
                card.isSelected = isSel
                card.background = GradientDrawable().apply {
                    cornerRadius = context.dpF(16f)
                    setColor(if (isSel) selectedFill else idleFill)
                    if (isSel) {
                        setStroke(context.dp(2), palette.textAccent)
                    } else {
                        setStroke(context.hairlinePx(), palette.cardHairline)
                    }
                }
                if (isSel) {
                    icon.background = GradientDrawable().apply {
                        cornerRadius = checkBoxRadius
                        setColor(palette.primary)
                    }
                    icon.setPadding(checkBoxPad, checkBoxPad, checkBoxPad, checkBoxPad)
                    icon.setImageResource(R.drawable.ic_check_24dp)
                    icon.imageTintList = ColorStateList.valueOf(palette.onPrimary)
                } else {
                    icon.background = GradientDrawable().apply {
                        cornerRadius = checkBoxRadius
                        setColor(Color.TRANSPARENT)
                        setStroke(checkBoxStrokePx, uncheckedStrokeColor)
                    }
                    icon.setPadding(0, 0, 0, 0)
                    icon.setImageDrawable(null)
                    icon.imageTintList = null
                }
            }
        }

        val quickVertInset = context.dp(6)
        fun quickPillBg(fillColor: Int): InsetDrawable = object : InsetDrawable(
            GradientDrawable().apply {
                cornerRadius = context.dpF(12f)
                setColor(fillColor)
            },
            0,
            quickVertInset,
            0,
            quickVertInset,
        ) {
            override fun getPadding(padding: Rect): Boolean {
                padding.setEmpty()
                return false
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(4), context.dp(20), context.dp(20))

            // 标题
            addView(context.label(context.getString(R.string.network_test_scope_title), 20f, 700).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            })

            // 描述
            addView(
                context.label(
                    context.getString(R.string.network_test_scope_desc),
                    13f,
                    400,
                    context.color(ceui.pixiv.witstudio.R.color.wit_text_2),
                ).apply { lineHeightRatio(1.4f) },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = context.dp(6)
                    bottomMargin = context.dp(6)
                },
            )

            // 快捷操作行：全选 / 反选（48dp 触控热区，上下透明沿保持视觉紧凑）
            val quickBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val allBtn = context.label(context.getString(R.string.network_test_scope_select_all), 13f, 600, palette.textTag).apply {
                    gravity = Gravity.CENTER
                    minHeight = context.dp(48)
                    setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
                    background = quickPillBg(selectedFill)
                    pressScale()
                    setOnClickListener {
                        if (confirmed) return@setOnClickListener
                        selected.addAll(NetworkTestModule.entries)
                        renderAll()
                    }
                }
                val invBtn = context.label(context.getString(R.string.network_test_scope_invert), 13f, 500, context.color(R.color.v3_text_2)).apply {
                    gravity = Gravity.CENTER
                    minHeight = context.dp(48)
                    setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
                    background = quickPillBg(idleFill)
                    pressScale()
                    setOnClickListener {
                        if (confirmed) return@setOnClickListener
                        val current = selected.toSet()
                        selected.clear()
                        NetworkTestModule.entries.forEach {
                            if (!current.contains(it)) selected.add(it)
                        }
                        renderAll()
                    }
                }
                addView(allBtn)
                addView(invBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(10) })
            }
            addView(quickBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })

            // 4 个胶囊+内嵌文本形态卡片
            ITEMS.forEach { item ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                    minimumHeight = context.dp(64)
                    pressScale()

                    // 第一行：标题 + 勾选状态图标
                    val topRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        val titleView = context.label(context.getString(item.titleRes), 15f, 600)
                        val icon = ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }

                        addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(icon, LinearLayout.LayoutParams(context.dp(22), context.dp(22)))
                    }
                    addView(topRow)

                    // 内嵌文本说明
                    val descView = context.label(
                        context.getString(item.descRes),
                        12f,
                        400,
                        context.color(ceui.pixiv.witstudio.R.color.wit_text_2),
                    ).apply {
                        lineHeightRatio(1.35f)
                    }
                    addView(descView, LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = context.dp(4)
                    })

                    val icon = (topRow.getChildAt(1) as ImageView)
                    cardViews.add(Triple(item.module, this, icon))

                    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = CheckBox::class.java.name
                            info.isCheckable = true
                            info.isChecked = host.isSelected
                        }
                    })

                    setOnClickListener {
                        if (confirmed) return@setOnClickListener
                        if (selected.contains(item.module)) {
                            selected.remove(item.module)
                        } else {
                            selected.add(item.module)
                        }
                        renderAll()
                    }
                }

                addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = context.dp(10)
                })
            }

            // 底部确认按钮（自适应高度避免大字体裁切）
            val confirmBtn = context.label(
                context.getString(R.string.network_test_scope_confirm),
                15f,
                600,
                palette.onPrimary,
            ).apply {
                gravity = Gravity.CENTER
                minHeight = context.dp(48)
                setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = context.dpF(999f)
                    setColor(palette.primary)
                }
                pressScale()
                setOnClickListener {
                    if (confirmed) return@setOnClickListener
                    if (selected.isEmpty()) {
                        Common.showToast(context.getString(R.string.network_test_scope_empty_warn))
                        return@setOnClickListener
                    }
                    confirmed = true
                    onScopePicked.onScopePicked(selected.toSet())
                    sheet.dismiss()
                }
            }
            addView(confirmBtn, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = context.dp(8)
            })
        }

        renderAll()

        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        sheet.setSheetContent(scroll)
        sheet.show()
        return sheet
    }
}