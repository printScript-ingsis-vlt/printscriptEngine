package com.printscript.domain

import com.printscript.domain.result.LineStatus
import com.printscript.domain.result.OutputLineResult
import com.printscript.domain.result.TestRunResult

/**
 * Compara la salida real de un snippet contra la esperada, linea por linea y en orden
 * (cada posicion es un println). No sabe nada de Spring ni del interprete: recibe listas y devuelve el resultado.
 *
 * - Si una lista es mas larga que la otra, las posiciones que sobran son FAIL con el lado faltante en null.
 * - Si la ejecucion tuvo errores el test no pasa, aunque las lineas impresas hasta el error coincidan.
 * - La comparacion es exacta (incluye espacios).
 */
class OutputComparator {

    fun compare(expected: List<String>, actual: List<String>, errors: List<String> = emptyList()): TestRunResult {
        val lines = (0 until maxOf(expected.size, actual.size)).map { index ->
            val expectedLine = expected.getOrNull(index)
            val actualLine = actual.getOrNull(index)
            OutputLineResult(index, expectedLine, actualLine, statusOf(expectedLine, actualLine))
        }
        val passedCount = lines.count { it.status == LineStatus.PASS }
        val failedCount = lines.size - passedCount

        return TestRunResult(
            passed = failedCount == 0 && errors.isEmpty(),
            total = lines.size,
            passedCount = passedCount,
            failedCount = failedCount,
            lines = lines,
            errors = errors,
        )
    }

    // Las dos en null no pasa nunca: el rango llega hasta la lista mas larga.
    private fun statusOf(expected: String?, actual: String?): LineStatus =
        if (expected != null && expected == actual) LineStatus.PASS else LineStatus.FAIL
}
