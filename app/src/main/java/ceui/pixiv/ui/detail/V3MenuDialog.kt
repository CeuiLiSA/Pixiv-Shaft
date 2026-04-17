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
import androidx.fragment.app.DialogFragment
import ceui.lisa.R

class V3MenuDialog : DialogFragment() {

    data class MenuItem(
        val label: String,
        @DrawableRes val icon: Int,
        val onClick: () -> Unit
    )

    private val items = mutableListOf<MenuItem>()

    fun addItem(label: String, @DrawableRes icon: Int, onClick: () -> Unit): V3MenuDialog {
        items.add(MenuItem(label, icon, onClick))
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
        if (items.isEmpty()) return

        val container = view.findViewById<LinearLayout>(R.id.menu_container)
        val inflater = LayoutInflater.from(requireContext())

        items.forEach { item ->
            val row = inflater.inflate(R.layout.item_v3_menu_row, container, false)
            row.findViewById<ImageView>(R.id.menu_icon).setImageResource(item.icon)
            row.findViewById<TextView>(R.id.menu_label).text = item.label
            row.setOnClickListener {
                item.onClick()
                dismiss()
            }
            container.addView(row)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.78).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
