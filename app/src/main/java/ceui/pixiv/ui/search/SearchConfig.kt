package ceui.pixiv.ui.search

import ceui.pixiv.ui.search.v3.AiMode
import ceui.pixiv.ui.search.v3.R18Mode

data class SearchConfig(
    val keyword: String,
    val sort: String = "date_desc",
    val usersYori: String = "",
    val search_target: String = "partial_match_for_tags",
    val merge_plain_keyword_results: Boolean = true,
    val include_translated_tag_results: Boolean = true,

    // V3 Filter — 全部走 pixiv 官方原生 query 参数，不再依赖 keyword hack。
    val bookmarkMin: Int? = null,
    val bookmarkMax: Int? = null,
    val tool: String? = null,        // illust only
    val genre: Int? = null,          // novel only
    val lang: String? = null,
    // 投稿期间：V3 不再发 within_last_* 的 duration 参数；durationBucket 已经在 SearchViewModel
    // 当场算成 start/end_date 塞进这两个字段。
    val startDate: String? = null,   // YYYY-MM-DD
    val endDate: String? = null,
    val searchAiType: Int = 0,       // 0 = include AI（默认）；1 = exclude AI（由 aiMode 派生）
    // AI 三档：屏蔽走 searchAiType 服务端；「仅看 AI」走 DataSource 客户端按 illust/novel_ai_type 过滤
    val aiMode: AiMode = AiMode.All,
    val isOriginalOnly: Boolean? = null,    // novel only
    val isReplaceableOnly: Boolean? = null, // novel only
    val ratioPattern: String? = null,       // illust/manga only: landscape | portrait | square
    // 作品类别（仅 illust/manga）—— content_type query 参数；
    // null = 不传（默认档「插画、漫画、动图」等价于不传）
    val contentType: String? = null,
    // 分辨率档位（仅 illust/manga）—— 4 个独立 query 参数，null 跳过
    val widthMin: Int? = null,
    val widthMax: Int? = null,
    val heightMin: Int? = null,
    val heightMax: Int? = null,
    // 正文长度 3 单位（仅 novel）—— iOS pixiv 8.6.6 抓包确认
    val textLengthMin: Int? = null,
    val textLengthMax: Int? = null,
    val wordCountMin: Int? = null,
    val wordCountMax: Int? = null,
    val readingTimeMin: Int? = null,
    val readingTimeMax: Int? = null,
    // R-18 限制三档 —— 客户端按 x_restrict 过滤（不再拼 -R-18 / R-18 关键字），
    // 由 DataSource 的 filter 钩子在 mapProtoItemsToHolders 时应用。
    val r18Mode: R18Mode = R18Mode.All,
)
