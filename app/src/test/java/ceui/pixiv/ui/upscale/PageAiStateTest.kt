package ceui.pixiv.ui.upscale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageAiStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun stateWithResult() =
        PageAiState(1L, 0, upscaleResultPath = tmp.newFile().absolutePath)

    @Test
    fun `a dismissed result stays acknowledged`() {
        val state = stateWithResult()
        assertTrue(state.hasUnacknowledgedResult)

        state.acknowledgeResult()

        assertFalse(state.hasUnacknowledgedResult)
    }

    @Test
    fun `a new result prompts again after the previous one was acknowledged`() {
        val state = stateWithResult()
        state.acknowledgeResult()

        state.rembgResultPath = tmp.newFile().absolutePath

        assertTrue(state.hasUnacknowledgedResult)
    }

    @Test
    fun `a result whose file is gone never prompts`() {
        val state = PageAiState(1L, 0, upscaleResultPath = "/nonexistent/result.png")

        assertFalse(state.hasUnacknowledgedResult)
    }
}
