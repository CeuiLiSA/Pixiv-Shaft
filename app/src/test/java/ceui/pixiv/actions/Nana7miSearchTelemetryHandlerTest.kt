package ceui.pixiv.actions

import ceui.lisa.activities.Shaft
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.shaftapi.Nana7miSearchTelemetryAck
import ceui.pixiv.shaftapi.Nana7miSearchTelemetryBatchAck
import ceui.pixiv.shaftapi.Nana7miSearchTelemetryBatchReq
import ceui.pixiv.shaftapi.Nana7miSearchTelemetryReq
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun `telemetry is disabled when the build has no signing secret`() {
        assertFalse(Nana7miSearchTelemetry.enabledForConfiguration(false))
        assertTrue(Nana7miSearchTelemetry.enabledForConfiguration(true))
    }

    @Test
    fun `failure labels are stable across wrappers and obfuscation`() {
        assertEquals(
            "network_timeout",
            nana7miTelemetryFailureType(IOException("outer", SocketTimeoutException("slow"))),
        )
        assertEquals(
            "network_connect",
            nana7miTelemetryFailureType(IOException("outer", ConnectException("refused"))),
        )
        assertEquals("decode_json", nana7miTelemetryFailureType(JsonSyntaxException("bad")))
        assertEquals("unexpected", nana7miTelemetryFailureType(IllegalStateException("bug")))
        assertEquals("http", nana7miTelemetryFailureType(httpError(503)))
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
        val telemetry = Nana7miSearchTelemetry(lazy { error("no Context needed before start()") })
        val flow = telemetry.beginFlow(
            requesterUid = 31660292L,
            contentType = Nana7miSearchTelemetry.ContentType.ILLUST,
            query = "初音ミク",
            initialRoute = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
            signerConfigured = true,
        )!!
        flow.borrowed(4867906L)

        val value = runBlocking {
            flow.track(Nana7miSearchTelemetry.Page.FIRST) { "business-result" }
        }
        assertEquals("business-result", value)

        val businessError = IllegalStateException("business-failure")
        val thrown = runCatching {
            runBlocking {
                flow.track<String>(Nana7miSearchTelemetry.Page.NEXT) { throw businessError }
            }
        }.exceptionOrNull()
        assertSame(businessError, thrown)
    }

    @Test
    fun `batch handler delivers one request for a whole flush`() = runBlocking {
        val events = List(3) { index ->
            validPayload().copy(eventId = "123e4567-e89b-42d3-a456-42661417400$index")
        }
        val sent = mutableListOf<Nana7miSearchTelemetryBatchReq>()
        val handler = Nana7miSearchTelemetryBatchHandler(
            isOnline = { true },
            report = {
                sent.add(it)
                Nana7miSearchTelemetryBatchAck(ok = true, accepted = it.events.size, stored = it.events.size)
            },
        )

        assertSame(ActionOutcome.Success, handler.execute(batchAction(events)))
        assertEquals(1, sent.size)
        assertEquals(events.map { it.eventId }, sent.single().events.map { it.eventId })
    }

    @Test
    fun `batch handler drops only the invalid events`() = runBlocking {
        val good = validPayload()
        val bad = validPayload().copy(
            eventId = "123e4567-e89b-42d3-a456-426614174005",
            requesterUid = 0L,
        )
        var sent: Nana7miSearchTelemetryBatchReq? = null
        val handler = Nana7miSearchTelemetryBatchHandler(
            isOnline = { true },
            report = {
                sent = it
                Nana7miSearchTelemetryBatchAck(ok = true, accepted = it.events.size, stored = it.events.size)
            },
        )

        assertSame(ActionOutcome.Success, handler.execute(batchAction(listOf(good, bad))))
        assertEquals(listOf(good.eventId), sent?.events?.map { it.eventId })
    }

    @Test
    fun `batch handler permanently drops a row with nothing deliverable`() = runBlocking {
        var calls = 0
        val handler = Nana7miSearchTelemetryBatchHandler(
            isOnline = { true },
            report = {
                calls += 1
                Nana7miSearchTelemetryBatchAck(ok = true)
            },
        )

        val empty = handler.execute(batchAction(listOf(validPayload().copy(requesterUid = 0L))))
        val unparsable = handler.execute(
            batchAction(emptyList()).copy(payload = """{"events":null}"""),
        )

        assertEquals(0, calls)
        assertEquals(
            "permanent telemetry failure: no valid events in batch",
            (empty as ActionOutcome.Fail).reason,
        )
        assertEquals(
            "permanent telemetry failure: unparsable batch payload",
            (unparsable as ActionOutcome.Fail).reason,
        )
    }

    @Test
    fun `batch handler upgrades events queued by previous app build`() = runBlocking {
        val legacy = validPayload().copy(eventType = null, appVersion = null, appChannel = null)
        var sent: Nana7miSearchTelemetryBatchReq? = null
        val handler = Nana7miSearchTelemetryBatchHandler(
            isOnline = { true },
            report = {
                sent = it
                Nana7miSearchTelemetryBatchAck(ok = true, accepted = 1, stored = 1)
            },
        )

        assertSame(ActionOutcome.Success, handler.execute(batchAction(listOf(legacy))))
        assertEquals("request", sent?.events?.single()?.eventType)
        assertEquals("unknown", sent?.events?.single()?.appVersion)
        assertEquals("unknown", sent?.events?.single()?.appChannel)
    }

    @Test
    fun `batch failures reuse the telemetry-only error policy`() = runBlocking {
        val permanent = Nana7miSearchTelemetryBatchHandler(
            isOnline = { true },
            report = { throw httpError(400) },
        ).execute(batchAction(listOf(validPayload()))) as ActionOutcome.Fail
        val serverFailure = Nana7miSearchTelemetryBatchHandler(
            isOnline = { true },
            report = { throw httpError(503) },
        ).execute(batchAction(listOf(validPayload()))) as ActionOutcome.Retry
        val notAcknowledged = Nana7miSearchTelemetryBatchHandler(
            isOnline = { true },
            report = { Nana7miSearchTelemetryBatchAck(ok = false) },
        ).execute(batchAction(listOf(validPayload()))) as ActionOutcome.Retry

        assertEquals("permanent telemetry failure: HTTP 400", permanent.reason)
        assertEquals(ceui.pixiv.actionqueue.RetryScope.QUEUE, serverFailure.scope)
        assertEquals(ceui.pixiv.actionqueue.RetryScope.ACTION, notAcknowledged.scope)
        assertEquals(
            "nana7mi telemetry batch not acknowledged",
            notAcknowledged.cause?.message,
        )
    }

    private fun batchAction(events: List<Nana7miSearchTelemetry.Payload>) = PendingAction(
        id = 2L,
        type = Nana7miSearchTelemetry.BATCH_ACTION_TYPE,
        dedupeKey = "${Nana7miSearchTelemetry.BATCH_ACTION_TYPE}:123e4567-e89b-42d3-a456-426614174777",
        payload = Shaft.sGson.toJson(Nana7miSearchTelemetry.BatchPayload(events)),
        attempt = 0,
        owner = "nana7mi_search_telemetry",
    )

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
