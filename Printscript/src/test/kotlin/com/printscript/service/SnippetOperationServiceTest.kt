package com.printscript.service

import com.printscript.domain.DefaultPrintScriptEngine
import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.repository.FileRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetOperationServiceTest {

    /** Repositorio en memoria que registra que keys se leyeron. */
    private class InMemoryFileRepository(vararg entries: Pair<String, String>) : FileRepository {
        val files = mutableMapOf(*entries)
        val reads = mutableListOf<String>()
        val writes = mutableListOf<String>()

        override fun get(key: String): String {
            reads.add(key)
            return files[key] ?: throw StorageFileNotFoundException(key)
        }

        override fun put(key: String, content: String) {
            writes.add(key)
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
    fun `format guarda el resultado en la key formatted y devuelve las dos keys`() {
        val result = service.format("snippet", "formatRules", "1.0")

        assertEquals("snippet", result.snippetKey)
        assertEquals("snippet.formatted", result.formattedKey)
        assertTrue(result.errors.isEmpty())
        assertTrue(repo.files.getValue("snippet.formatted").contains("mi_variable: number=5;"))
    }

    @Test
    fun `format nunca pisa la original`() {
        val original = repo.files.getValue("snippet")

        service.format("snippet", "formatRules", "1.0")

        assertEquals(original, repo.files.getValue("snippet"))
        assertEquals(setOf("snippet.formatted"), repo.writes.toSet())
    }

    @Test
    fun `formatear de nuevo reemplaza el formatted anterior`() {
        repo.put("snippet.formatted", "viejo")

        service.format("snippet", "formatRules", "1.0")

        assertTrue(repo.files.getValue("snippet.formatted") != "viejo")
    }

    @Test
    fun `si el snippet no compila no escribe nada y devuelve formattedKey null`() {
        repo.files["roto"] = "let x: number = ;"

        val result = service.format("roto", null, "1.0")

        assertEquals("roto", result.snippetKey)
        assertNull(result.formattedKey)
        assertTrue(result.errors.isNotEmpty())
        assertFalse(repo.files.containsKey("roto.formatted"))
        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun `validate acepta cualquier key, como un draft`() {
        repo.files["snippet.draft"] = "let x: number = ;"

        assertFalse(service.validate("snippet.draft", "1.0").valid)
        assertEquals(listOf("snippet.draft"), repo.reads)
        assertTrue(repo.writes.isEmpty())
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
