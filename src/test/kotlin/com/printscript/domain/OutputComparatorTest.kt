package com.printscript.domain

import com.printscript.domain.result.LineStatus
import com.printscript.domain.result.OutputLineResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputComparatorTest {

    private val comparator = OutputComparator()

    @Test
    fun `todas las lineas iguales pasa`() {
        val result = comparator.compare(listOf("1", "hola"), listOf("1", "hola"))

        assertTrue(result.passed)
        assertEquals(2, result.total)
        assertEquals(2, result.passedCount)
        assertEquals(0, result.failedCount)
        assertEquals(
            listOf(
                OutputLineResult(0, "1", "1", LineStatus.PASS),
                OutputLineResult(1, "hola", "hola", LineStatus.PASS),
            ),
            result.lines,
        )
    }

    @Test
    fun `una linea distinta falla solo esa posicion`() {
        val result = comparator.compare(listOf("1", "2", "3"), listOf("1", "5", "3"))

        assertFalse(result.passed)
        assertEquals(2, result.passedCount)
        assertEquals(1, result.failedCount)
        assertEquals(OutputLineResult(1, "2", "5", LineStatus.FAIL), result.lines[1])
    }

    @Test
    fun `si el snippet imprime de menos las que faltan fallan con actual null`() {
        val result = comparator.compare(listOf("1", "2"), listOf("1"))

        assertFalse(result.passed)
        assertEquals(2, result.total)
        assertEquals(OutputLineResult(1, "2", null, LineStatus.FAIL), result.lines[1])
    }

    @Test
    fun `si el snippet imprime de mas las que sobran fallan con expected null`() {
        val result = comparator.compare(listOf("1"), listOf("1", "extra"))

        assertFalse(result.passed)
        assertEquals(2, result.total)
        assertEquals(OutputLineResult(1, null, "extra", LineStatus.FAIL), result.lines[1])
    }

    @Test
    fun `sin output esperado ni real pasa`() {
        val result = comparator.compare(emptyList(), emptyList())

        assertTrue(result.passed)
        assertEquals(0, result.total)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `con errores de ejecucion no pasa aunque las lineas coincidan`() {
        val result = comparator.compare(listOf("1"), listOf("1"), listOf("Division por cero"))

        assertFalse(result.passed)
        assertEquals(1, result.passedCount)
        assertEquals(listOf("Division por cero"), result.errors)
    }

    @Test
    fun `la comparacion es exacta con los espacios`() {
        val result = comparator.compare(listOf("hola"), listOf("hola "))

        assertFalse(result.passed)
        assertEquals(LineStatus.FAIL, result.lines[0].status)
    }
}
