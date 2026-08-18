package ceui.pixiv.session

import ceui.lisa.repo.freshMembershipOf
import ceui.pixiv.login.PixivOAuthUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 上报给借号池的 `is_premium` 该听谁的。
 *
 * 背景：这个值原本是**登录那一刻冻下来的**，之后没有任何一条路径会再读一次 ——
 * `applyTokenRefresh` 明说保留旧 metadata，静默同步只合并 user/detail 的 `user` 对象
 * （那里面根本没有 is_premium，真值在同一份响应的 `profile` 里），而 TokenInterceptor
 * 上报的又是 [ceui.lisa.utils.Local] 那份连静默同步都收不到的 SharedPreferences 副本。
 * 于是会员过期后一直报「有会员」——号继续被派发出去，借到的人白花一次额度，然后降级；
 * 登录后才买的会员一直报「没有会员」——自己的号进不了池子。
 */
class SessionPremiumReportTest {

    /**
     * pixiv 这次亲口说的最新，其次是会话（由前台静默同步按 `profile.is_premium` 维护），
     * 最后才是调用方手上那份旧值。
     */
    @Test
    fun `the freshest source wins`() {
        assertEquals(false, resolvePremiumForReport(fresh = false, session = true, stored = true))
        assertEquals(true, resolvePremiumForReport(fresh = true, session = false, stored = false))
    }

    /**
     * pixiv 有些刷新响应就是不带会员字段。「没说」必须退到会话那份，而不是当成
     * 「不是会员」—— 那会把一个正在付费的号从池子里踢出去。
     */
    @Test
    fun `a refresh that says nothing falls back instead of demoting`() {
        assertEquals(true, resolvePremiumForReport(fresh = null, session = true, stored = false))
        assertEquals(false, resolvePremiumForReport(fresh = null, session = false, stored = true))
    }

    /**
     * 会话也没有值（老会话 / 迁移过来的数据）才轮到那份冻结的旧值。它排在最后，正是
     * 因为它是全 app 唯一一处不会更新的会员状态。
     */
    @Test
    fun `the frozen legacy copy is the last resort, never the first`() {
        assertEquals(true, resolvePremiumForReport(fresh = null, session = null, stored = true))
        assertEquals(false, resolvePremiumForReport(fresh = null, session = null, stored = false))
    }

    /**
     * 刷新响应只能替它自己指名的那个账号说话 —— 和借号那支 [freshMembershipOf] 是同一条
     * 规则，[SessionManager.freshPremiumOf] 直接复用它，两处不会漂移。
     */
    @Test
    fun `a refresh only speaks for the account it names`() {
        val uid = 31660292L
        assertEquals(true, freshMembershipOf(PixivOAuthUser(uid, "n", "a", isPremium = true), uid))
        assertNull(freshMembershipOf(PixivOAuthUser(999L, "n", "a", isPremium = false), uid))
        // id 缺失/非数字时 pixiv-login 填 0，绑不到本账号
        assertNull(freshMembershipOf(PixivOAuthUser(0L, "n", "a", isPremium = false), uid))
        // 未登录/会话没加载完时 loggedInUid 也是 0，两个 0 会相等 —— 所以
        // SessionManager.freshPremiumOf 在调用前自己挡了一道 uid > 0，见那里的注释
        assertEquals(false, freshMembershipOf(PixivOAuthUser(0L, "n", "a", isPremium = false), 0L))
    }
}
