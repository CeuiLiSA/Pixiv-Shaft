package ceui.pixiv.ui.detail

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import kotlin.math.roundToInt

class V3MenuDialog : DialogFragment() {

    sealed interface MenuItem {
        val label: String
        val icon: Int

        data class Action(
            override val label: String,
            @DrawableRes override val icon: Int,
            val onClick: () -> Unit,
        ) : MenuItem

        data class Toggle(
            override val label: String,
            @DrawableRes override val icon: Int,
            val isChecked: () -> Boolean,
            val onToggle: (Boolean) -> Unit,
        ) : MenuItem
    }

    private val items = mutableListOf<MenuItem>()
    private val toggles = mutableListOf<Pair<SwitchCompat, MenuItem.Toggle>>()

    fun setItems(newItems: List<MenuItem>): V3MenuDialog {
        items.clear()
        items.addAll(newItems)
        return this
    }

    fun addItem(label: String, @DrawableRes icon: Int, onClick: () -> Unit): V3MenuDialog {
        items.add(MenuItem.Action(label, icon, onClick))
        return this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If recreated by system, items will be empty — just dismiss
        if (items.isEmpty() && savedInstanceState != null) {
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.5f)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_v3_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 面板底色跟搜索筛选器卡片一致：隐约主题色 tint（XML bg_v3_menu_dialog 是固定中性
        // v3_menu_bg，切主题色不动）。沿用原 24dp 圆角 + 1dp 描边。
        val d = resources.displayMetrics.density
        view.background = V3Palette.from(requireContext())
            .settingsCardBg(24f * d, (1 * d).roundToInt())
        if (items.isEmpty()) return

        val container = view.findViewById<LinearLayout>(R.id.menu_container)
        val inflater = LayoutInflater.from(requireContext())

        items.forEach { item ->
            val rowLayout = if (item is MenuItem.Toggle) R.layout.item_v3_menu_switch_row
                else R.layout.item_v3_menu_row
            val row = inflater.inflate(rowLayout, container, false)
            val icon = row.findViewById<ImageView>(R.id.menu_icon)
            icon.setImageResource(item.icon)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getColor(R.color.v3_text_2)
            )
            row.findViewById<TextView>(R.id.menu_label).text = item.label
            when (item) {
                is MenuItem.Action -> row.setOnClickListener {
                    dismiss()
                    item.onClick()
                }
                is MenuItem.Toggle -> {
                    val toggle = row.findViewById<SwitchCompat>(R.id.menu_switch)
                    toggle.contentDescription = item.label
                    toggle.isChecked = item.isChecked()
                    toggle.setOnClickListener {
                        item.onToggle(toggle.isChecked)
                        toggle.isChecked = item.isChecked()
                    }
                    row.setOnClickListener { toggle.performClick() }
                    row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    toggles.add(toggle to item)
                }
            }
            container.addView(row)
        }
    }

    override fun onDestroyView() {
        toggles.clear()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        toggles.forEach { (toggle, item) -> toggle.isChecked = item.isChecked() }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.78).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

class V3MenuBuilder {
    private val items = mutableListOf<V3MenuDialog.MenuItem>()

    fun item(label: String, @DrawableRes icon: Int, onClick: () -> Unit) {
        items.add(V3MenuDialog.MenuItem.Action(label, icon, onClick))
    }

    fun switchItem(
        label: String,
        @DrawableRes icon: Int,
        isChecked: () -> Boolean,
        onToggle: (Boolean) -> Unit,
    ) {
        items.add(V3MenuDialog.MenuItem.Toggle(label, icon, isChecked, onToggle))
    }

    internal fun build(): List<V3MenuDialog.MenuItem> = items.toList()
}

fun Fragment.showV3Menu(tag: String = "V3Menu", builder: V3MenuBuilder.() -> Unit) {
    val items = V3MenuBuilder().apply(builder).build()
    if (items.isEmpty()) return
    V3MenuDialog().setItems(items).show(childFragmentManager, tag)
}
