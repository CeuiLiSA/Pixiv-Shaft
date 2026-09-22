package ceui.pixiv.banner.host

import android.app.Application
import android.util.LruCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.pixiv.banner.BannerManager
import ceui.pixiv.banner.BannerRequest
import ceui.pixiv.banner.RealBannerManager
import ceui.pixiv.chat.api.ShaftChatGateway
import ceui.pixiv.websocket.IncomingMessage
import ceui.pixiv.websocket.WebSocketClient
import ceui.pixiv.websocket.WebSocketConfig
import ceui.pixiv.websocket.WebSocketEvent
import ceui.pixiv.websocket.WebSocketManager
import ceui.pixiv.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okio.ByteString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real bridge and decoder on Android; fixtures only inject inbound frames, never send messages. */
@RunWith(AndroidJUnit4::class)
class ChatBannerChannelTest {
    @Test
    fun privateMessagesRespectChannelWithEitherPublicBannerSetting() = runBlocking {
        for (publicEnabled in listOf(false, true)) {
            checkDelivery("144115188075855873", publicEnabled, !BuildConfig.IS_LITE)
        }
    }

    @Test
    fun restoredPublicBannerSettingCannotEnableLiteBanners() = runBlocking {
        checkDelivery("global", publicEnabled = true, expectedBanner = !BuildConfig.IS_LITE)
    }

    @Test
    fun disabledPublicBannersStayDisabledOnBothChannels() = runBlocking {
        checkDelivery("global", publicEnabled = false, expectedBanner = false)
    }

    private suspend fun checkDelivery(room: String, publicEnabled: Boolean, expectedBanner: Boolean) {
        // Gateway/portrait-cache injection uses private fields in this fixture, so run it against
        // googleDebug/githubDebug. The production channel gate is the same in release builds.
        assumeTrue("Fixture requires an unminified app", BuildConfig.DEBUG)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val previousSettings = Shaft.sSettings
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val socket = IncomingOnlySocket()
        val sockets = WebSocketManager(flowOf(true), { socket },
            WebSocketConfig(url = "ws://fixture.invalid"), Dispatchers.Unconfined)
        val gateway = ShaftChatGateway(app)
        ShaftChatGateway::class.java.getDeclaredField("manager").apply { isAccessible = true }
            .set(gateway, sockets)
        val delegate = RealBannerManager(emptyMap(), parentContext = Dispatchers.Unconfined)
        val requests = mutableListOf<BannerRequest>()
        val banners = object : BannerManager by delegate {
            override fun enqueue(request: BannerRequest): Boolean {
                requests += request
                return true
            }
        }
        val bridge = ChatBannerBridge(app, banners, scope, gateway)
        // A cached fixture avatar prevents the non-Lite control case from making a real API call.
        @Suppress("UNCHECKED_CAST")
        val avatars = ChatBannerBridge::class.java.getDeclaredField("avatarCache")
            .apply { isAccessible = true }.get(bridge) as LruCache<Long, String>
        val sender = Long.MAX_VALUE - 1
        avatars.put(sender, "https://fixture.invalid/avatar.png")
        try {
            Shaft.sSettings = Settings().apply { isShowChatRoomPushBanner = publicEnabled }
            sockets.start()
            delegate.onHostStarted()
            bridge.start()
            socket.incoming.emit(IncomingMessage.Text(
                """{"kind":"msg","room":"$room","uid":$sender,"display_name":"fixture","client_msg_id":"channel-check","text":"channel check","ts":1}""",
            ))
            assertEquals("room=$room publicEnabled=$publicEnabled lite=${BuildConfig.IS_LITE}",
                if (expectedBanner) 1 else 0, requests.size)
            requests.singleOrNull()?.let {
                assertEquals("channel check", (it as BannerRequest.Text).message)
            }
        } finally {
            bridge.stop()
            scope.cancel()
            sockets.shutdown()
            delegate.shutdown()
            Shaft.sSettings = previousSettings
        }
    }

    private class IncomingOnlySocket : WebSocketClient {
        override val state = MutableStateFlow<WebSocketState>(WebSocketState.Idle)
        override val events = MutableSharedFlow<WebSocketEvent>()
        override val incoming = MutableSharedFlow<IncomingMessage>()
        override fun connect() = Unit
        override fun disconnect(code: Int, reason: String) = Unit
        override fun cancel() = Unit
        override fun close() = Unit
        override fun send(text: String): Boolean = error("Test must never send messages")
        override fun send(bytes: ByteString): Boolean = error("Test must never send messages")
        override suspend fun sendSuspending(text: String): Boolean = error("Test must never send messages")
        override suspend fun sendSuspending(bytes: ByteString): Boolean = error("Test must never send messages")
    }
}
