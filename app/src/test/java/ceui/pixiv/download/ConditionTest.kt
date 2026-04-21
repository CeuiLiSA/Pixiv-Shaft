package ceui.pixiv.download

import ceui.pixiv.download.template.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConditionTest {

    @Test fun `parses flag condition`() {
        val c = Condition.parse("R18")
        assertEquals(Condition.Flag("R18", negated = false), c)
    }

    @Test fun `parses negated flag`() {
        val c = Condition.parse("!R18")
        assertEquals(Condition.Flag("R18", negated = true), c)
    }

    @Test fun `parses page greater than`() {
        val c = Condition.parse("p>3")
        assertEquals(Condition.PageGreaterThan(3), c)
    }

    @Test fun `trims surrounding whitespace`() {
        assertEquals(Condition.Flag("AI"), Condition.parse("  AI  "))
    }

    @Test fun `malformed page threshold is error`() {
        assertThrows(IllegalStateException::class.java) { Condition.parse("p>abc") }
    }

    @Test fun `empty condition is error`() {
        assertThrows(IllegalArgumentException::class.java) { Condition.parse("") }
    }
}
