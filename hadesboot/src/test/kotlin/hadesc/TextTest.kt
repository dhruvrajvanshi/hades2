package hadesc

import hadesc.text.Config
import hadesc.text.Text
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {
    @BeforeEach
    fun beforeEach() {
        Config.branchingFactor = 3
        Config.maxChunkSize = 3
    }

    @Test
    fun `offsetOf for string with one line`() {
        val str = Text.from("foo")
        assertEquals(0, str.offsetOf(1, 1))
        assertEquals(1, str.offsetOf(1, 2))
    }

    @Test
    fun `offsetOf for string with multiple lines`() {
        val str = Text.from("foo\nbar\nbax")
        assertEquals(4, str.offsetOf(2, 1))
        assertEquals(5, str.offsetOf(2, 2))
    }

    @Test
    fun `indexing into Interior nodes works correctly`() {
        val str = Text.from("012345678901234567890")
        assertEquals('0', str[0])
        assertEquals('1', str[1])
        assertEquals('2', str[2])
        assertEquals('0', str[10])
        assertEquals('1', str[11])
    }
}
