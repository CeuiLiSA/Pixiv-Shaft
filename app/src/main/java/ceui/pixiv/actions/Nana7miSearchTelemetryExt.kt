package ceui.pixiv.actions

/**
 * 搜索 repo 用的可空 [Nana7miSearchTelemetry.Flow] 包装：telemetry 关掉（`beginFlow` 返回 null，
 * Lite / 未配签名 / 参数越界）时直接跑请求，开着时套一层事件上报。语义与 [Nana7miSearchTelemetry.Flow.track]
 * / [Nana7miSearchTelemetry.Flow.observeFirst] 一致，只是把 `telemetry?.track(...) ?: source` 这种
 * Rx 时代的三目写法收成一处。
 */
internal suspend fun <T : Any> Nana7miSearchTelemetry.Flow?.trackOrRun(
    page: Nana7miSearchTelemetry.Page,
    source: suspend () -> T,
): T = if (this == null) source() else track(page, source = source)

internal suspend fun <T : Any> Nana7miSearchTelemetry.Flow?.trackOrRun(
    page: Nana7miSearchTelemetry.Page,
    route: Nana7miSearchTelemetry.Route,
    borrowedUid: Long?,
    eventId: String?,
    reason: String? = if (route == this?.routeNow) this?.reasonNow else null,
    source: suspend () -> T,
): T = if (this == null) {
    source()
} else {
    track(page, route, borrowedUid, reason, eventId, source)
}

internal suspend fun <T : Any> Nana7miSearchTelemetry.Flow?.observeFirstOrRun(
    source: suspend () -> T,
): T = if (this == null) source() else observeFirst(source)
