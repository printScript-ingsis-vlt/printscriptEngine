package com.printscript.messaging

import com.printscript.domain.DefaultPrintScriptEngine
import com.printscript.domain.errors.StorageUnavailableException
import com.printscript.fakes.InMemoryFileRepository
import com.printscript.service.SnippetOperationService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleRequestProcessorTest {

    private val files = InMemoryFileRepository(
        "snippet" to "let mi_variable: number = 5;\nprintln(mi_variable);",
        "roto" to "let x: number = ;",
        "snake" to """{"identifier-format":"snake-case"}""",
        "camel" to """{"identifier-format":"camel-case"}""",
        "badJson" to "{esto no es json",
        "noSpaces" to """{"enforce-no-spacing-around-equals":true}""",
    )
    private val processor = RuleRequestProcessor(SnippetOperationService(files, DefaultPrintScriptEngine()))

    private fun request(snippetKey: String = "snippet", rulesKey: String? = null, version: String = "1.0") =
        RuleRequest("req-1", "snip-1", snippetKey, rulesKey, "printscript", version)

    @Test
    fun `lint sin issues es COMPLIANT`() {
        val result = processor.lint(request(rulesKey = "snake"))

        assertEquals(LintStatus.COMPLIANT, result.status)
        assertEquals("req-1", result.requestId)
        assertEquals("snip-1", result.snippetId)
    }

    @Test
    fun `lint con issues es NOT_COMPLIANT y lleva las issues`() {
        val result = processor.lint(request(rulesKey = "camel"))

        assertEquals(LintStatus.NOT_COMPLIANT, result.status)
        assertEquals("identifier-format", result.issues.single().rule)
    }

    @Test
    fun `lint de codigo que no compila es FAILED con los errores`() {
        val result = processor.lint(request(snippetKey = "roto"))

        assertEquals(LintStatus.FAILED, result.status)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `errores permanentes se responden FAILED en vez de reintentar`() {
        listOf(
            request(rulesKey = "badJson"),
            request(version = "9.9"),
            request(snippetKey = "borrado"),
            request(rulesKey = "reglas-que-no-existen"),
        ).forEach {
            val result = processor.lint(it)
            assertEquals(LintStatus.FAILED, result.status, "pedido: $it")
            assertTrue(result.errors.single().isNotBlank())
        }
    }

    @Test
    fun `si el Asset Service esta caido propaga la excepcion para reintentar`() {
        files.unavailable = true

        assertThrows<StorageUnavailableException> { processor.lint(request()) }
        assertThrows<StorageUnavailableException> { processor.format(request()) }
    }

    @Test
    fun `format devuelve el codigo formateado y no escribe en el Asset Service`() {
        val result = processor.format(request(rulesKey = "noSpaces"))

        assertEquals(FormatStatus.FORMATTED, result.status)
        assertNotNull(result.formatted)
        assertTrue(result.formatted!!.contains("mi_variable: number=5;"))
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `format de codigo que no compila es FAILED sin formatted`() {
        val result = processor.format(request(snippetKey = "roto"))

        assertEquals(FormatStatus.FAILED, result.status)
        assertNull(result.formatted)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `format con reglas invalidas es FAILED`() {
        assertEquals(FormatStatus.FAILED, processor.format(request(rulesKey = "badJson")).status)
    }
}
