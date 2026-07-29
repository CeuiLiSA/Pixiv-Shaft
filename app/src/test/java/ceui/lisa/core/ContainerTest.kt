package ceui.lisa.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContainerTest {

    private val container = Container.get()

    @Before
    fun setUp() {
        container.clear()
    }

    @After
    fun tearDown() {
        container.clear()
    }

    @Test
    fun `page lifecycle is explicit`() {
        val page = PageData("page-id", null, emptyList())

        container.addPageToMap(page)
        assertSame(page, container.getPage("page-id"))

        container.removePage("page-id")
        assertNull(container.getPage("page-id"))
    }

    @Test
    fun `pagination gate is shared per page but isolated across pages`() {
        val first = PageData("first", null, emptyList())
        val second = PageData("second", null, emptyList())

        assertTrue(first.tryStartNextPageLoad())
        assertFalse(first.tryStartNextPageLoad())
        assertTrue(second.tryStartNextPageLoad())

        first.finishNextPageLoad()
        second.finishNextPageLoad()
        assertTrue(first.tryStartNextPageLoad())
        first.finishNextPageLoad()
    }
}
