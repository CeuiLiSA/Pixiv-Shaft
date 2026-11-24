package ceui.pixiv.nana7mi.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

/**
 * Verifies Java callers have clean, ergonomic, zero-Kotlin-ceremony access to the SDK's builders,
 * policies, models, observer composition, and engine lifecycle.
 */
public class JavaInteropTest {

    @Test
    public void buildersAndPoliciesAreIdiomaticFromJava() {
        NetworkRetryPolicy retryPolicy = NetworkRetryPolicy.builder()
                .totalBudgetMs(10_000L)
                .retryDelayMs(100L)
                .minRetryWindowMs(500L)
                .maxRetries(2)
                .build();
        assertEquals(10_000L, retryPolicy.getTotalBudgetMs());
        assertEquals(2, retryPolicy.getMaxRetries());

        BorrowedAccount<String> account = BorrowedAccount.builder("host-dto")
                .uid(102L)
                .accessToken("secret-at")
                .refreshToken("secret-rt")
                .updatedAtMs(1_000L)
                .expiresAtMs(5_000L)
                .isPremium(true)
                .build();
        assertEquals("Bearer secret-at", account.authorizationHeader());
        assertFalse(account.toString().contains("secret-at"));

        RateLimitInfo limit = RateLimitInfo.builder()
                .scope("uid_5h")
                .resetsAtMs(2_000L)
                .serverTimeMs(1_000L)
                .build();
        assertTrue(limit.matchesScope(Collections.singleton("uid_5h")));
        assertEquals(Long.valueOf(1_000L), limit.remainingResetMs());

        assertEquals(Boolean.TRUE, MembershipPolicy.attribute(102L, 102L, true));
        assertNull(MembershipPolicy.attribute(999L, 102L, false));
        assertEquals(Boolean.TRUE, MembershipPolicy.merge(true, null));

        SessionObserver observer = SessionObserver.composite(SessionObserver.NO_OP, event -> {});
        assertNotNull(observer);

        InMemoryAccountReportStore<String> store = new InMemoryAccountReportStore<>();
        Nana7miSessionConfig<String> config = Nana7miSessionConfig.<String>builder()
                .dispatcher((uid, reqId, cont) -> AcquireResult.NoAccount.INSTANCE)
                .tokenRefresher((uid, rt, cont) -> new TokenRefreshOutcome.Failed(new RuntimeException()))
                .payloadUpdater((prev, tokens, premium) -> prev)
                .reportStore(store)
                .retryPolicy(retryPolicy)
                .accessPolicy(SessionAccessPolicy.ALWAYS_ALLOWED)
                .clock(SessionClock.SYSTEM)
                .requestIdGenerator(RequestIdGenerator.UUID)
                .observer(observer)
                .build();

        try (Nana7miSessionEngine<String> engine = new Nana7miSessionEngine<>(config)) {
            assertFalse(engine.isClosed());
            BorrowedSession<String> session = engine.newSession();
            assertEquals(BorrowedSessionState.Idle.INSTANCE, session.getState());
        }
    }
}
