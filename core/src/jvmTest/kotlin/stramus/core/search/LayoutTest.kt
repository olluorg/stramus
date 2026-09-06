package stramus.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayoutTest {

    @Test
    fun `english typed with the russian layout still on`() {
        assertEquals("kotlin", swapLayout("лщедшт"))
        assertEquals("github", swapLayout("пшерги"))
        assertEquals("search", swapLayout("ыуфкср"))
    }

    @Test
    fun `russian typed with the english layout still on`() {
        assertEquals("привет", swapLayout("ghbdtn"))
        assertEquals("вкладки", swapLayout("drkflrb"))
    }

    @Test
    fun `a host survives the dot it was broken across`() {
        // "." sits on the key that prints "ю", so a wrongly-typed host carries one in the middle.
        assertEquals("github.com", swapLayout("пшергиюсщь"))
        assertEquals("kotlinlang.org", swapLayout("лщедштдфтпющкп"))
    }

    @Test
    fun `case is left as it was typed`() {
        assertEquals("Kotlin", swapLayout("Лщедшт"))
        assertEquals("GitHub", swapLayout("ПшеРги"))
    }

    @Test
    fun `a query in one script and one layout has nothing to swap`() {
        assertNull(swapLayout(""), "nothing typed")
        assertNull(swapLayout("123 456"), "no letters to read differently")
        // Both scripts at once: this was typed on purpose, whatever it is.
        assertNull(swapLayout("kotlin вкладки"))
    }

    @Test
    fun `what is not on the map is left alone`() {
        // Digits and spaces sit on the same keys in both layouts and print the same thing.
        assertEquals("kotlin 2", swapLayout("лщедшт 2"))
    }

    @Test
    fun `converting twice comes back to where it started`() {
        val typed = "лщедшт"
        val meant = swapLayout(typed)!!
        assertEquals(typed, swapLayout(meant))
    }
}
