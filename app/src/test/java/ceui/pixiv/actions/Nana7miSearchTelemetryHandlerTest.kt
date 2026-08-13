package ceui.pixiv.actions

import ceui.lisa.activities.Shaft
import ceui.loxia.Nana7miSearchTelemetryAck
import ceui.loxia.Nana7miSearchTelemetryReq
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.PendingAction
import com.google.gson.Gson
import io.reactivex.Observable
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class Nana7miSearchTelemetryHandlerTest {

    @Before
    fun setUp() {
        Shaft.sGson = Gson()
    }

    @Test
    fun `handler reports generic novel pagination failure`() = runBlocking {
        val payload = Nana7miSearchTelemetry.Payload(
            eventId = "123e4567-e89b-42d3-a456-426614174000",
            flowId = "123e4567-e89b-42d3-a456-426614174001",
            eventType = "request",
            occurredAt = 2_000_000_000_000L,
            requesterUid = 31660292L,
            borrowedUid = 4867906L,
            query = "初音ミク",
            contentType = "novel",
            page = "next",
            route = "borrowed_official",
            outcome = "failure",
            flowOutcome = null,
            reason = "http_500",
            errorType = "HttpException",
            httpStatus = 500,
            durationMs = null,
            appVersion = "6.8.0-debug",
            appChannel = "github",
        )
        var reported: Nana7miSearchTelemetryReq? = null
        val handler = Nana7miSearchTelemetryHandler(
            isOnline = { true },
            report = { request ->
                reported = request
                Nana7miSearchTelemetryAck(
                    ok = true,
                    eventId = request.eventId,
                    duplicate = false,
                )
            },
        )

        val outcome = handler.execute(action(payload))

        assertSame(ActionOutcome.Success, outcome)
        assertEquals(payload.eventId, reported?.eventId)
        assertEquals(payload.flowId, reported?.flowId)
        assertEquals("request", reported?.eventType)
        assertEquals(payload.requesterUid, reported?.requesterUid)
        assertEquals(payload.borrowedUid, reported?.borrowedUid)
        assertEquals(payload.query, reported?.query)
        assertEquals("novel", reported?.contentType)
        assertEquals("next", reported?.page)
        assertEquals("failure", reported?.outcome)
    }

    @Test
    fun `handler accepts preview fallback without borrower`() = runBlocking {
        val payload = validPayload().copy(
            borrowedUid = null,
            contentType = "illust",
            route = "preview_fallback",
            reason = "no_account",
        )
        val handler = Nana7miSearchTelemetryHandler(
            isOnline = { true },
            report = { Nana7miSearchTelemetryAck(ok = true, eventId = it.eventId) },
        )

        assertSame(ActionOutcome.Success, handler.execute(action(payload)))
    }

    @Test
    fun `handler permanently rejects invalid requester`() = runBlocking {
        var calls = 0
        val handler = Nana7miSearchTelemetryHandler(
            isOnline = { true },
            report = {
                calls += 1
                Nana7miSearchTelemetryAck(ok = true, eventId = it.eventId)
            },
        )

        val outcome = handler.execute(action(validPayload().copy(requesterUid = 0L)))

        assertEquals(0, calls)
        assertEquals(
            "permanent telemetry failure: invalid payload",
            (outcome as ActionOutcome.Fail).reason,
        )
    }

    @Test
    fun `telemetry 400 is permanent instead of entering the six-hour retry loop`() = runBlocking {
        val handler = Nana7miSearchTelemetryHandler(
            isOnline = { true },
            report = { throw httpError(400) },
        )

        val outcome = handler.execute(action(validPayload())) as ActionOutcome.Fail

        assertEquals("permanent telemetry failure: HTTP 400", outcome.reason)
    }

    @Test
    fun `auth and missing route failures survive staggered deployments`() = runBlocking {
        for (code in listOf(401, 403, 404)) {
            val outcome = Nana7miSearchTelemetryHandler(
                isOnline = { true },
                report = { throw httpError(code) },
            ).execute(action(validPayload())) as ActionOutcome.Retry

            assertEquals(ceui.pixiv.actionqueue.RetryScope.QUEUE, outcome.scope)
        }
    }

    @Test
    fun `terminal success route and flow outcome must agree`() = runBlocking {
        var calls = 0
        val handler = Nana7miSearchTelemetryHandler(
            isOnline = { true },
            report = {
                calls += 1
                Nana7miSearchTelemetryAck(ok = true, eventId = it.eventId)
            },
        )
        val inconsistent = validPayload().copy(
            eventType = "flow_terminal",
            route = "borrowed_official",
            outcome = "success",
            flowOutcome = "fallback_success",
            durationMs = 100L,
        )

        val outcome = handler.execute(action(inconsistent)) as ActionOutcome.Fail

        assertEquals(0, calls)
        assertEquals("permanent telemetry failure: invalid payload", outcome.reason)
    }

    @Test
    fun `telemetry backend failures cool only the dedicated telemetry queue`() = runBlocking {
        val serverFailure = Nana7miSearchTelemetryHandler(
            isOnline = { true },
            report = { throw httpError(503) },
        ).execute(action(validPayload())) as ActionOutcome.Retry
        val offlineFailure = Nana7miSearchTelemetryHandler(
            isOnline = { false },
            report = { throw IOException("offline") },
        ).execute(action(validPayload())) as ActionOutcome.Retry

        assertEquals(ceui.pixiv.actionqueue.RetryScope.QUEUE, serverFailure.scope)
        assertEquals(ceui.pixiv.actionqueue.RetryScope.QUEUE, offlineFailure.scope)
        assertFalse(offlineFailure.countsAsAttempt)
    }

    @Test
    fun `acknowledgement mismatch is parked for backend recovery`() = runBlocking {
        val handler = Nana7miSearchTelemetryHandler(
            isOnline = { true },
            report = {
                Nana7miSearchTelemetryAck(
                    ok = true,
                    eventId = "123e4567-e89b-42d3-a456-426614174099",
                )
            },
        )

        val outcome = handler.execute(action(validPayload())) as ActionOutcome.Retry

        assertEquals(ceui.pixiv.actionqueue.RetryScope.ACTION, outcome.scope)
        assertEquals("nana7mi telemetry acknowledgement mismatch", outcome.cause?.message)
    }

    @Test
    fun `handler upgrades telemetry queued by previous app build`() = runBlocking {
        var reported: Nana7miSearchTelemetryReq? = null
        val legacy = validPayload().copy(eventType = null, appVersion = null, appChannel = null)
        val handler = Nana7miSearchTelemetryHandler(
            isOnline = { true },
            report = {
                reported = it
                Nana7miSearchTelemetryAck(ok = true, eventId = it.eventId)
            },
        )

        assertSame(ActionOutcome.Success, handler.execute(action(legacy)))
        assertEquals("request", reported?.eventType)
        assertEquals("unknown", reported?.appVersion)
        assertEquals("unknown", reported?.appChannel)
    }

    @Test
    fun `tracking never changes source success or failure when telemetry is unavailable`() {
        val flow = Nana7miSearchTelemetry.start(
            requesterUid = 31660292L,
            contentType = Nana7miSearchTelemetry.ContentType.ILLUST,
            query = "初音ミク",
            initialRoute = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
        )!!
        flow.borrowed(4867906L)

        flow.track(Observable.just("business-result"), Nana7miSearchTelemetry.Page.FIRST)
            .test()
            .assertValue("business-result")
            .assertComplete()

        val businessError = IllegalStateException("business-failure")
        flow.track(
            Observable.error<String>(businessError),
            Nana7miSearchTelemetry.Page.NEXT,
        )
            .test()
            .assertError(businessError)
    }

    private fun action(payload: Nana7miSearchTelemetry.Payload) = PendingAction(
        id = 1L,
        type = "nana7mi_search_telemetry",
        dedupeKey = "nana7mi_search_telemetry:${payload.eventId}",
        payload = Shaft.sGson.toJson(payload),
        attempt = 0,
        owner = "nana7mi_search_telemetry",
    )

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody()))

    private fun validPayload() = Nana7miSearchTelemetry.Payload(
        eventId = "123e4567-e89b-42d3-a456-426614174000",
        flowId = "123e4567-e89b-42d3-a456-426614174001",
        eventType = "request",
        occurredAt = 2_000_000_000_000L,
        requesterUid = 31660292L,
        borrowedUid = 4867906L,
        query = "初音ミク",
        contentType = "illust",
        page = "first",
        route = "borrowed_official",
        outcome = "success",
        flowOutcome = null,
        reason = null,
        errorType = null,
        httpStatus = null,
        durationMs = null,
        appVersion = "6.8.0-debug",
        appChannel = "github",
    )
}
