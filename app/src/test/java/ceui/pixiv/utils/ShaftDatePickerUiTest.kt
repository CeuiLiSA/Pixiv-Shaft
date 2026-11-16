package ceui.pixiv.utils

import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.lisa.databinding.DialogSearchFilterDateRangeBinding
import ceui.pixiv.ui.search.v3.DateRangePickerSheet
import ceui.pixiv.ui.search.v3.SearchFilterV3
import ceui.pixiv.utils.ShaftDatePicker.toUtcEpochMilli
import com.google.android.material.datepicker.MaterialDatePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class ShaftDatePickerUiTest {
    private val minDate = LocalDate.of(1926, 1, 1)
    private val maxDate = LocalDate.of(2026, 11, 16)

    @Test
    fun `birthday older than the current lower bound opens at the first allowed date`() {
        withHost { host ->
            val picker = show(host, LocalDate.of(1925, 12, 31))
            assertEquals(minDate.toUtcEpochMilli(), picker.selection)
        }
    }

    @Test
    fun `initial date beyond the last month opens at the last allowed date`() {
        withHost { host ->
            val picker = show(host, LocalDate.of(2026, 12, 1))
            assertEquals(maxDate.toUtcEpochMilli(), picker.selection)
        }
    }

    @Test
    fun `initial date after the maximum within its month is clamped too`() {
        withHost { host ->
            val picker = show(host, maxDate.plusDays(1))
            assertEquals(maxDate.toUtcEpochMilli(), picker.selection)
        }
    }

    @Test
    fun `calendar and keyboard input inflate in the app theme`() {
        withHost { host ->
            checkCalendarAndKeyboardInput(host)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-night")
    fun `calendar and keyboard input inflate in the dark app theme`() {
        withHost { host -> checkCalendarAndKeyboardInput(host) }
    }

    @Test
    fun `search range continues to the end picker and commits both dates`() {
        withHost { host ->
            val manager = host.supportFragmentManager
            var result: DateRangePickerSheet.Patch? = null
            manager.setFragmentResultListener("range", host) { _, bundle ->
                @Suppress("DEPRECATION")
                result = bundle.getSerializable(DateRangePickerSheet.KEY_PATCH) as DateRangePickerSheet.Patch
            }
            val sheet = DateRangePickerSheet.newInstance("range", SearchFilterV3())
            sheet.show(manager, "range-sheet")
            manager.executePendingTransactions()
            val startPicker = manager.findFragmentByTag("DateRangePickerDate") as MaterialDatePicker<*>
            startPicker.requireView().findViewById<android.view.View>(com.google.android.material.R.id.confirm_button).performClick()
            manager.executePendingTransactions()
            val endPicker = manager.findFragmentByTag("DateRangePickerDate") as MaterialDatePicker<*>
            assertEquals(host.getString(R.string.search_filter_v3_date_end),
                endPicker.requireView().findViewById<TextView>(com.google.android.material.R.id.mtrl_picker_title_text).text)
            endPicker.requireView().findViewById<android.view.View>(com.google.android.material.R.id.confirm_button).performClick()
            manager.executePendingTransactions()
            DialogSearchFilterDateRangeBinding.bind(sheet.requireView()).btnConfirm.performClick()
            assertEquals(DateRangePickerSheet.Patch(LocalDate.now().toString(), LocalDate.now().toString()), result)
        }
    }

    private fun checkCalendarAndKeyboardInput(host: FragmentActivity) {
        val picker = show(host, LocalDate.of(2000, 2, 29))
        assertNotNull(picker.requireView().findViewById<TextView>(com.google.android.material.R.id.mtrl_picker_header_selection_text))
        picker.requireView().findViewById<android.view.View>(com.google.android.material.R.id.mtrl_picker_header_toggle).performClick()
        host.supportFragmentManager.executePendingTransactions()
        assertNotNull(picker.requireView().findViewById<android.view.View>(com.google.android.material.R.id.mtrl_picker_edit_text_date))
    }

    private fun show(host: FragmentActivity, selectedDate: LocalDate): MaterialDatePicker<*> {
        ShaftDatePicker.show(
            fragmentManager = host.supportFragmentManager,
            tag = "test-date",
            selectedDate = selectedDate,
            minDate = minDate,
            maxDate = maxDate,
            onDateSelected = {},
        )
        host.supportFragmentManager.executePendingTransactions()
        return host.supportFragmentManager.findFragmentByTag("test-date") as MaterialDatePicker<*>
    }

    private fun withHost(block: (FragmentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        try {
            block(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
