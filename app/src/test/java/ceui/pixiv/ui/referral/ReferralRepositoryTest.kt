package ceui.pixiv.ui.referral

import ceui.pixiv.shaftapi.PixshaftApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ReferralRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: ReferralRepository
    private val plans = mutableListOf<String>()

    @Before fun setup() {
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(PixshaftApi::class.java)
        repository = ReferralRepository({ api }, { uid, plan -> plans += "$uid:${plan.key}" })
    }
    @After fun close() { server.shutdown() }

    @Test fun activationRefreshesSharedPlanAndUsesTheSelectedCardId() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"state":{"uid":42,"campaign":"old","plan":{"key":"max"},"activeUntil":999}}"""))
        val result = repository.activate(123, "old") as ReferralResult.Success
        assertEquals(999L, result.value.activeUntil)
        assertEquals(listOf("42:max"), plans)
        val request = server.takeRequest()
        assertEquals("/v1/referral/activate", request.path)
        assertEquals("""{"id":123,"campaign":"old"}""", request.body.readUtf8())
    }

    @Test fun missingActionStateIsFailureRatherThanInventedEmptySuccess() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}"))
        val result = repository.claim(ReferralTask.INVITE) as ReferralResult.Failure
        assertTrue(result.failure.stale)
    }

    @Test fun conflictPreservesRefreshInstructionAndExtendedDeadline() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"higher_tier_active","expiresAt":1234}"""))
        val failure = (repository.activate(1) as ReferralResult.Failure).failure
        assertEquals("higher_tier_active", failure.code)
        assertEquals(1234L, failure.expiresAt)
        assertTrue(failure.stale)
    }

    @Test fun bookmarkHttpFailureIsRetryableAndTheOwnerTravelsWithTheBeacon() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("{}"))
        assertFalse(repository.reportBookmark(42))
        assertTrue(repository.reportBookmark(42))
        assertEquals("""{"bookmarked":true,"uid":42}""", server.takeRequest().body.readUtf8())
        server.takeRequest()
    }

    @Test fun historicalCampaignIsExplicitOnReadsAndClaims() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"campaign":"old"}"""))
        repository.load("old")
        assertEquals("/v1/referral/state?campaign=old", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"state":{"campaign":"old"}}"""))
        repository.claim(ReferralTask.TUTORIAL, "old")
        assertEquals("""{"task":"tutorial","campaign":"old"}""", server.takeRequest().body.readUtf8())
    }
}
