package ceui.pixiv.ui.user.following

import android.content.Context
import ceui.lisa.R
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.createResponseStore
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class TimelineFollowingPostsDataSource(
    private val args: FollowingPostFragmentArgs,
    private val context: Context,
) : DataSource<Illust, IllustResponse>(
    dataFetcher = {
        Client.appApi.followUserPosts(
            args.objectType,
            args.restrictType ?: Params.TYPE_ALL
        )
    },
    responseStore = createResponseStore({ "timeline-following-${args.objectType}-${args.restrictType}" }),
    itemMapper = { illust -> listOf(TimelinePostHolder(illust)) },
    filter = { illust -> illust.isAuthurExist() }
) {
    override fun updateHolders(holders: List<ListItemHolder>) {
        val timelineHolders = mutableListOf<ListItemHolder>()
        var lastDateLabel: String? = null

        val postHolders = holders.filterIsInstance<TimelinePostHolder>()

        postHolders.forEachIndexed { index, holder ->
            val dateLabel = getDateLabel(holder.illust.create_date)

            if (dateLabel != lastDateLabel) {
                timelineHolders.add(
                    TimelineDateHeaderHolder(
                        dateText = dateLabel,
                        isFirst = lastDateLabel == null
                    )
                )
                lastDateLabel = dateLabel
            }

            val isLastInList = index == postHolders.size - 1
            timelineHolders.add(
                TimelinePostHolder(
                    illust = holder.illust,
                    isFirst = false,
                    isLast = isLastInList
                )
            )
        }

        super.updateHolders(timelineHolders)
    }

    private fun getDateLabel(createDate: String?): String {
        if (createDate.isNullOrEmpty()) return context.getString(R.string.string_390)

        return try {
            val zonedDateTime = ZonedDateTime.parse(createDate, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val postDate = zonedDateTime.toLocalDate()
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)

            when (postDate) {
                today -> context.getString(R.string.timeline_today)
                yesterday -> context.getString(R.string.timeline_yesterday)
                else -> {
                    if (postDate.year == today.year) {
                        postDate.format(DateTimeFormatter.ofPattern("M/d"))
                    } else {
                        postDate.format(DateTimeFormatter.ofPattern("yyyy/M/d"))
                    }
                }
            }
        } catch (e: DateTimeParseException) {
            context.getString(R.string.string_390)
        }
    }
}
