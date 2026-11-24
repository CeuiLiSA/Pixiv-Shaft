package ceui.pixiv.nana7mi.host

import ceui.pixiv.actions.AccountOnlineReportOutbox
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.nana7mi.session.BorrowedSession
import ceui.pixiv.nana7mi.session.Nana7miSessionEngine
import ceui.pixiv.nana7mi.session.NetworkRetryExecutor
import ceui.pixiv.nana7mi.session.SerialExecutionCoordinator
import ceui.pixiv.nana7mi.session.SessionObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped composition root wiring `:nana7mi`'s pure-JVM [Nana7miSessionEngine] to
 * Pixiv-Shaft's network, OAuth, durable outbox, quota notice, and Timber adapters.
 *
 * Cheap to construct (no IO, no coroutines, no global state reads) so Hilt can inject it during
 * `Shaft.super.onCreate()` alongside the rest of [ceui.pixiv.services.ServicesProvider].
 */
@Singleton
class ShaftNana7miSession internal constructor(
    val engine: Nana7miSessionEngine<AccountResponse>,
) {
    @Inject
    constructor(outbox: AccountOnlineReportOutbox) : this(
        engine = Nana7miSessionEngine.create {
            dispatcher = PixshaftAccountDispatcher()
            tokenRefresher = PixivOAuthTokenRefresher()
            payloadUpdater = PixivAccountPayloadUpdater
            reportStore = OutboxAccountReportStore(outbox)
            authFailureClassifier = PixivOAuthFailureClassifier
            accessPolicy = ShaftSessionAccessPolicy
            observer = SessionObserver.composite(
                TimberSessionObserver,
                QuotaNoticeSessionObserver,
            )
        },
    )

    /** Creates one generation-isolated borrowed session for a search repository's first page. */
    fun newSession(): BorrowedSession<AccountResponse> = engine.newSession()

    val retryExecutor: NetworkRetryExecutor get() = engine.retryExecutor
    val serialCoordinator: SerialExecutionCoordinator get() = engine.serialCoordinator
}
