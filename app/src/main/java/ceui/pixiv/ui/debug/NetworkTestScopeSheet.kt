package ceui.pixiv.ui.debug

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
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
    ) {
        val sheet = WitBottomSheet(context)
        val selected = currentSelection.toMutableSet()
        val palette = V3Palette.from(context)
        val surface = context.color(R.color.v3_bg)
        val idleFill = ColorUtils.compositeColors(context.color(R.color.v3_surface_3), surface)
        val selectedFill = ColorUtils.compositeColors(palette.alpha20, surface)

        val cardViews = mutableListOf<Triple<NetworkTestModule, LinearLayout, ImageView>>()

        fun renderAll() {
            cardViews.forEach { (module, card, icon) ->
                val isSel = selected.contains(module)
                card.background = GradientDrawable().apply {
                    cornerRadius = context.dpF(16f)
                    setColor(if (isSel) selectedFill else idleFill)
                    if (isSel) {
                        setStroke(context.dp(2), palette.primary)
                    } else {
                        setStroke(context.dp(1), ColorUtils.setAlphaComponent(palette.cardHairline, 120))
                    }
                }
                icon.setImageResource(if (isSel) R.drawable.ic_check_24dp else R.drawable.ic_checkbox_off)
                icon.imageTintList = ColorStateList.valueOf(
                    if (isSel) palette.primary else context.color(R.color.v3_text_3),
                )
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
                    bottomMargin = context.dp(12)
                },
            )

            // 快捷操作行：全选 / 反选
            val quickBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val allBtn = context.label(context.getString(R.string.network_test_scope_select_all), 13f, 600, palette.textTag).apply {
                    setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = context.dpF(12f)
                        setColor(selectedFill)
                    }
                    pressScale()
                    setOnClickListener {
                        selected.addAll(NetworkTestModule.entries)
                        renderAll()
                    }
                }
                val invBtn = context.label(context.getString(R.string.network_test_scope_invert), 13f, 500, context.color(R.color.v3_text_2)).apply {
                    setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = context.dpF(12f)
                        setColor(idleFill)
                    }
                    pressScale()
                    setOnClickListener {
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
            addView(quickBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(14) })

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

                    setOnClickListener {
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

            // 底部确认按钮
            val confirmBtn = TextView(context).apply {
                text = context.getString(R.string.network_test_scope_confirm)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.onPrimary)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = context.dpF(24f)
                    setColor(palette.primary)
                }
                pressScale()
                setOnClickListener {
                    if (selected.isEmpty()) {
                        Common.showToast(context.getString(R.string.network_test_scope_empty_warn))
                        return@setOnClickListener
                    }
                    onScopePicked.onScopePicked(selected)
                    sheet.dismiss()
                }
            }
            addView(confirmBtn, LinearLayout.LayoutParams(-1, context.dp(48)).apply {
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
    }
}