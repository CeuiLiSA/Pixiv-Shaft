package ceui.pixiv.ui.referral

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import ceui.pixiv.shaftapi.PixshaftApi
import ceui.pixiv.shaftapi.ReferralActionResponse
import ceui.pixiv.shaftapi.ReferralRewardDto
import ceui.pixiv.shaftapi.ReferralStateResponse
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ReferralPlanViewModelTest {
    @get:Rule val executor = InstantTaskExecutorRule()

    private class Api {
        val calls = mutableListOf<Pair<String, CompletableDeferred<Response<*>>>>()
        val arguments = mutableListOf<List<Any?>>()
        val proxy = Proxy.newProxyInstance(PixshaftApi::class.java.classLoader, arrayOf(PixshaftApi::class.java)) { _, method, args ->
            val answer = CompletableDeferred<Response<*>>()
            calls += method.name to answer
            arguments += args!!.dropLast(1)
            @Suppress("UNCHECKED_CAST")
            val continuation = args!!.last() as Continuation<Response<*>>
            (suspend { answer.await() }).startCoroutine(continuation)
            COROUTINE_SUSPENDED
        } as PixshaftApi
        fun answer(index: Int, body: Any) { calls[index].second.complete(Response.success(body)) }
    }

    @Test fun oldRefreshCannotOverwriteAClaimAndCancelledWritesRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = Api()
            val vm = ReferralPlanViewModel(SavedStateHandle(), ReferralRepository({ api.proxy }, { _, _ -> }), { 1L })
            store.put("referral", vm)
            runCurrent()
            api.answer(0, ReferralStateResponse(campaign = "current"))
            runCurrent()
            vm.refresh()
            runCurrent()
            val claim = async { vm.claim(ReferralTask.INVITE) }
            runCurrent()
            assertEquals(listOf("referralState", "referralState"), api.calls.map { it.first })
            api.answer(1, ReferralStateResponse(campaign = "current"))
            runCurrent()
            assertEquals("referralClaim", api.calls[2].first)
            val withCard = ReferralStateResponse(campaign = "current", rewards = listOf(ReferralRewardDto(id = 7, task = "invite", plan = "pro", days = 7)))
            api.answer(2, ReferralActionResponse(state = withCard))
            runCurrent()
            assertNull(claim.await())
            assertEquals(7L, vm.value.snapshot.cards.single().id)
            val activate = async { vm.activate(7) }
            runCurrent()
            activate.cancel()
            runCurrent()
            assertEquals("referralState", api.calls.last().first)
            api.answer(api.calls.lastIndex, withCard)
            runCurrent()
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun switchingCampaignDoesNotAcceptThePreviousCampaignResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = Api()
            val vm = ReferralPlanViewModel(SavedStateHandle(), ReferralRepository({ api.proxy }, { _, _ -> }), { 1L })
            store.put("referral", vm)
            runCurrent()
            vm.campaign("old")
            api.answer(0, ReferralStateResponse(campaign = "current"))
            runCurrent()
            assertTrue(vm.value.loading)
            api.answer(1, ReferralStateResponse(campaign = "old"))
            runCurrent()
            assertEquals("old", vm.value.snapshot.campaign)
            assertFalse(vm.value.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun queuedMutationCannotRunUnderAnotherAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = Api()
            var uid = 1L
            val vm = ReferralPlanViewModel(SavedStateHandle(), ReferralRepository({ api.proxy }, { _, _ -> }), { uid })
            store.put("referral", vm)
            runCurrent()
            val claim = async { vm.claim(ReferralTask.INVITE) }
            runCurrent()
            uid = 2L
            api.answer(0, ReferralStateResponse(uid = 1, campaign = "current"))
            runCurrent()
            assertNotNull(claim.await())
            assertFalse(api.calls.any { it.first == "referralClaim" })
            api.answer(api.calls.lastIndex, ReferralStateResponse(uid = 2, campaign = "current"))
            runCurrent()
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun anotherAccountRestartsOnTheCurrentCampaignAndCannotShowWrongUidData() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = Api()
            val saved = SavedStateHandle(mapOf("ownerUid" to 1L, "campaign" to "old"))
            val vm = ReferralPlanViewModel(saved, ReferralRepository({ api.proxy }, { _, _ -> }), { 2L })
            store.put("referral", vm)
            runCurrent()
            assertNull(saved.get<String>("campaign"))
            assertNull(api.arguments[0][1])
            api.answer(0, ReferralStateResponse(uid = 1, campaign = "current", code = "K7M2QX4P"))
            runCurrent()
            assertNull(vm.value.snapshot.code)
            assertNotNull(vm.value.error)
            assertEquals(1, api.calls.size)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
