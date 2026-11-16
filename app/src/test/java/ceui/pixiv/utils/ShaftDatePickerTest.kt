package ceui.pixiv.utils

import ceui.pixiv.utils.ShaftDatePicker.toLocalDateFromUtc
import ceui.pixiv.utils.ShaftDatePicker.toUtcEpochMilli
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ShaftDatePickerTest {

    @Test
    fun `LocalDate and UTC millis conversion is lossless across days`() {
        val testDates = listOf(
            LocalDate.of(2007, 9, 10),
            LocalDate.of(2008, 1, 1),
            LocalDate.of(2020, 2, 29), // 闰年闰日
            LocalDate.of(2026, 11, 15),
            LocalDate.of(2030, 12, 31)
        )

        for (date in testDates) {
            val utcMillis = date.toUtcEpochMilli()
            val convertedBack = utcMillis.toLocalDateFromUtc()
            assertEquals("Lossless conversion failed for date $date", date, convertedBack)
        }
    }
}
