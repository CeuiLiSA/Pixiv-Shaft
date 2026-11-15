package ceui.pixiv.utils

import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 官方 MaterialDatePicker 封装与工具类。
 *
 * 统一处理 UTC 毫秒与 LocalDate 之间的无偏移转换，并兜底应用 MaterialCalendar 主题。
 */
object ShaftDatePicker {

    fun interface OnDateSelectedListener {
        fun onDateSelected(date: LocalDate)
    }

    @JvmStatic
    @JvmOverloads
    fun show(
        fragmentManager: FragmentManager,
        tag: String = "ShaftDatePicker",
        title: CharSequence? = null,
        titleResId: Int = 0,
        selectedDate: LocalDate? = null,
        minDate: LocalDate? = null,
        maxDate: LocalDate? = null,
        listener: OnDateSelectedListener,
    ) {
        show(fragmentManager, tag, title, titleResId, selectedDate, minDate, maxDate) { picked ->
            listener.onDateSelected(picked)
        }
    }

    fun show(
        fragmentManager: FragmentManager,
        tag: String = "ShaftDatePicker",
        title: CharSequence? = null,
        titleResId: Int = 0,
        selectedDate: LocalDate? = null,
        minDate: LocalDate? = null,
        maxDate: LocalDate? = null,
        onDateSelected: (LocalDate) -> Unit,
    ) {
        val builder = MaterialDatePicker.Builder.datePicker()
        if (titleResId != 0) {
            builder.setTitleText(titleResId)
        } else if (title != null) {
            builder.setTitleText(title)
        }

        val selectedUtcMillis = selectedDate?.toUtcEpochMilli()
        if (selectedUtcMillis != null) {
            builder.setSelection(selectedUtcMillis)
        }

        val constraintsBuilder = CalendarConstraints.Builder()
        val validators = mutableListOf<CalendarConstraints.DateValidator>()

        val minUtcMillis = minDate?.toUtcEpochMilli()
        val maxUtcMillis = maxDate?.toUtcEpochMilli()

        if (minUtcMillis != null) {
            constraintsBuilder.setStart(minUtcMillis)
            validators.add(DateValidatorPointForward.from(minUtcMillis))
        }
        if (maxUtcMillis != null) {
            constraintsBuilder.setEnd(maxUtcMillis)
            validators.add(DateValidatorPointBackward.before(maxUtcMillis))
        }

        if (validators.isNotEmpty()) {
            constraintsBuilder.setValidator(
                if (validators.size == 1) validators.first() else CompositeDateValidator.allOf(validators)
            )
        }

        if (selectedUtcMillis != null) {
            constraintsBuilder.setOpenAt(selectedUtcMillis)
        } else if (maxUtcMillis != null) {
            constraintsBuilder.setOpenAt(maxUtcMillis)
        }

        builder.setCalendarConstraints(constraintsBuilder.build())
        builder.setTheme(com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)

        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selectionMillis ->
            val picked = selectionMillis.toLocalDateFromUtc()
            onDateSelected(picked)
        }
        picker.show(fragmentManager, tag)
    }

    @JvmStatic
    fun LocalDate.toUtcEpochMilli(): Long =
        atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @JvmStatic
    fun Long.toLocalDateFromUtc(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
}
