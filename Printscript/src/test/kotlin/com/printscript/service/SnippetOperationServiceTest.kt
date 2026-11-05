package com.printscript.service

import com.printscript.domain.DefaultPrintScriptEngine
import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.repository.FileRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnippetOperationServiceTest {

    /** Repositorio en memoria que registra que keys se leyeron. */
    private class InMemoryFileRepository(vararg entries: Pair<String, String>) : FileRepository {
        val files = mutableMapOf(*entries)
        val reads = mutableListOf<String>()

        override fun get(key: String): String {
            reads.add(key)
            return files[key] ?: throw StorageFileNotFoundException(key)
        }

        override fun put(key: String, content: String) {
            files[key] = content
        }
    }

    private val repo = InMemoryFileRepository(
        "snippet" to "let mi_variable: number = 5;\nprintln(mi_variable);",
        "snakeRules" to """{"identifier-format":"snake-case"}""",
        "camelRules" to """{"identifier-format":"camel-case"}""",
        "formatRules" to """{"enforce-no-spacing-around-equals":true}""",
    )
    private val service = SnippetOperationService(repo, DefaultPrintScriptEngine())

    @Test
    fun `interpret lee el snippet y lo ejecuta`() {
        val result = service.interpret("snippet", "1.0", null)

        assertEquals(listOf("5"), result.output)
        assertEquals(listOf("snippet"), repo.reads)
    }

    @Test
    fun `lint usa las reglas guardadas en el bucket`() {
        assertTrue(service.lint("snippet", "snakeRules", "1.0").success())
        assertFalse(service.lint("snippet", "camelRules", "1.0").success())
        assertEquals(listOf("snippet", "snakeRules", "snippet", "camelRules"), repo.reads)
    }

    @Test
    fun `sin rulesKey no busca reglas y usa los defaults`() {
        service.lint("snippet", null, "1.0")
        service.format("snippet", "  ", "1.0")

        assertEquals(listOf("snippet", "snippet"), repo.reads)
    }

    @Test
    fun `format aplica las reglas guardadas`() {
        val result = service.format("snippet", "formatRules", "1.0")

        assertTrue(result.formatted!!.contains("mi_variable: number=5;"), result.formatted)
    }

    @Test
    fun `validate lee el snippet y lo valida`() {
        assertTrue(service.validate("snippet", "1.0").valid)
    }

    @Test
    fun `si el snippet no existe propaga StorageFileNotFoundException`() {
        assertThrows<StorageFileNotFoundException> { service.validate("no-existe", "1.0") }
    }

    @Test
    fun `si las reglas no existen propaga StorageFileNotFoundException`() {
        assertThrows<StorageFileNotFoundException> { service.lint("snippet", "no-existe", "1.0") }
    }
}
