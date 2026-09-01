package ceui.pixiv.api.model

/** GET /v1/illust/report/topic-list 响应：举报第一步的违规类型列表，服务端动态下发。 */
data class IllustReportTopicListResponse(
    val topic_list: List<IllustReportTopic> = listOf(),
)

data class IllustReportTopic(
    val topic_id: Int = 0,
    val topic_title: String? = null,
)
