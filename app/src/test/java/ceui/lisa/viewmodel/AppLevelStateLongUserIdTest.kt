package ceui.lisa.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppLevelStateLongUserIdTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun `follow states use long ids as distinct keys while status stays int`() {
        val state = AppLevelState()
        val lowId = 42L
        val highId = (1L shl 32) + lowId

        state.updateFollowUserStatus(lowId, AppLevelState.FollowUserStatus.FOLLOWED_PUBLIC)
        state.updateFollowUserStatus(highId, AppLevelState.FollowUserStatus.FOLLOWED_PRIVATE)

        val lowStatus: Int? = state.getFollowUserLiveData(lowId).value
        val highStatus: Int? = state.getFollowUserLiveData(highId).value
        assertEquals(AppLevelState.FollowUserStatus.FOLLOWED_PUBLIC, lowStatus)
        assertEquals(AppLevelState.FollowUserStatus.FOLLOWED_PRIVATE, highStatus)
    }
}
