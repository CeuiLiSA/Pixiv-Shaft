package ceui.loxia

import android.content.Context
import android.text.format.DateUtils
import ceui.lisa.R
import java.time.ZonedDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Calendar
import java.util.Locale

object DateParse {

    private const val FAKE_DATE = "2022-03-21 08:24"
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun displayCreateDate(create_date: String?): String {
        if (create_date?.isNotEmpty() == true) {
            return if (create_date.contains("T") && create_date.length == 25) {
                val str = create_date.substring(0, 16)
                str.replace("T", "  ")
            } else {
                FAKE_DATE
            }
        }
        return FAKE_DATE
    }

    fun getTimeAgo(context: Context, createDate: String?): String {
        if (createDate.isNullOrEmpty()) {
            return ""
        }

        val zonedDateTime = ZonedDateTime.parse(createDate, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val timeInMillis = zonedDateTime.toInstant().toEpochMilli()

        if (timeInMillis == 0L) {
            return ""
        }
        val date = Calendar.getInstance()
        date.timeInMillis = timeInMillis

        val now = Calendar.getInstance()

        val intervalSec = (now.timeInMillis / 1000) - timeInMillis / 1000
        val mins = intervalSec.toInt() / 60

        return when {
            mins < 1 -> {
                context.resources.getString(R.string.date_minute_plurals_zero)
            }

            mins in 1..59 -> {
                context.resources.getQuantityString(R.plurals.recent_visits_date_minute, mins, mins)
            }

            else -> {
                when {
                    date.get(Calendar.YEAR) != now.get(Calendar.YEAR) -> {
                        DateUtils.formatDateTime(
                            context,
                            timeInMillis,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                        )
                    }

                    date.get(Calendar.MONTH) != now.get(Calendar.MONTH) -> {
                        DateUtils.formatDateTime(
                            context,
                            timeInMillis,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
                        )
                    }

                    date.get(Calendar.DAY_OF_MONTH) != now.get(Calendar.DAY_OF_MONTH) -> {
                        val days = now.get(Calendar.DAY_OF_MONTH) - date.get(Calendar.DAY_OF_MONTH)
                        context.resources.getQuantityString(R.plurals.date_day, days, days)
                    }

                    else -> {
                        val hours = intervalSec.toInt() / 3600
                        context.resources.getQuantityString(R.plurals.date_hour, hours, hours)
                    }
                }
            }
        }
    }

}