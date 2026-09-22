package ceui.pixiv.chat.api

import android.app.Application
import ceui.pixiv.chat.data.ChatDatabase
import ceui.pixiv.websocket.WebSocketManager
import ceui.pixiv.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ShaftChatGatewayTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var gateway: ShaftChatGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway = ShaftChatGateway(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        ReflectionHelpers.getField<CoroutineScope>(gateway, "scope").cancel()
        // Do not initialise an unused manager just to tear it down.
        gateway.javaClass.declaredFields.firstOrNull { it.name == "manager\$delegate" }?.let {
            it.isAccessible = true
            val manager = it.get(gateway) as Lazy<*>
            if (manager.isInitialized()) (manager.value as WebSocketManager).shutdown()
        }
        ReflectionHelpers.getStaticField<ChatDatabase?>(ChatDatabase::class.java, "instance")?.close()
        ReflectionHelpers.setStaticField(ChatDatabase::class.java, "instance", null)
        Dispatchers.resetMain()
    }

    @Test
    fun `restored chat screens can subscribe before deferred bootstrap`() = runTest(dispatcher) {
        // Room list reads incoming; the room itself reads the stream and side channels.
        val flows: List<Flow<*>> = listOf(
            gateway.incoming,
            gateway.state,
            gateway.chatStream.observe("global"),
            gateway.helloFrames,
            gateway.errorFrames,
            gateway.typingFrames,
            gateway.fatalAuth,
            gateway.replacedByOtherDevice,
            gateway.globalSendEnabled,
        )
        val subscriptions = flows.map { flow -> backgroundScope.launch { flow.collect {} } }
        runCurrent()

        assertEquals(WebSocketState.Idle, gateway.state.value)
        assertTrue(subscriptions.all { it.isActive })
        assertFalse(gateway.send(null, "before-bootstrap", "hello"))
        assertFalse(gateway.sendTyping(42L))
        val manager = ReflectionHelpers.getField<Lazy<WebSocketManager>>(gateway, "manager\$delegate").value
        assertFalse(ReflectionHelpers.getField<Boolean>(manager, "started"))
    }

    @Test
    fun `bootstrap keeps the streams already held by restored screens`() = runTest(dispatcher) {
        val incoming = gateway.incoming
        val state = gateway.state
        val stream = gateway.chatStream
        val hello = gateway.helloFrames
        val errors = gateway.errorFrames
        val typing = gateway.typingFrames
        val subscription = backgroundScope.launch { incoming.collect {} }
        runCurrent()

        gateway.bootstrap()
        gateway.bootstrap()
        runCurrent()

        assertSame(incoming, gateway.incoming)
        assertSame(state, gateway.state)
        assertSame(stream, gateway.chatStream)
        assertSame(hello, gateway.helloFrames)
        assertSame(errors, gateway.errorFrames)
        assertSame(typing, gateway.typingFrames)
        assertTrue(subscription.isActive)
        val manager = ReflectionHelpers.getField<Lazy<WebSocketManager>>(gateway, "manager\$delegate").value
        assertTrue(ReflectionHelpers.getField<Boolean>(manager, "started"))
    }
}
