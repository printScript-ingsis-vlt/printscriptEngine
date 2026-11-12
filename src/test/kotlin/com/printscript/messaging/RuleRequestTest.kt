package com.printscript.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuleRequestTest {

    private val fixture: Map<String, String> =
        jacksonObjectMapper().readValue(javaClass.getResource("/contracts/rule-request.v1.json")!!)

    @Test
    fun `parsea el pedido del contrato v1`() {
        val request = RuleRequest.from(fixture)

        assertEquals(fixture["requestId"], request.requestId)
        assertEquals(fixture["snippetId"], request.snippetId)
        assertEquals(fixture["snippetKey"], request.snippetKey)
        assertEquals("rules.user-123.lint", request.rulesKey)
        assertEquals("printscript", request.language)
        assertEquals("1.1", request.version)
    }

    @Test
    fun `sin snippetKey usa el snippetId y rulesKey vacio es reglas por defecto`() {
        val request = RuleRequest.from(fixture - "snippetKey" + ("rulesKey" to ""))

        assertEquals(fixture["snippetId"], request.snippetKey)
        assertNull(request.rulesKey)
    }

    @Test
    fun `falla si falta un campo obligatorio`() {
        listOf("requestId", "snippetId", "language", "version").forEach { field ->
            assertThrows<InvalidMessageException> { RuleRequest.from(fixture - field) }
            assertThrows<InvalidMessageException> { RuleRequest.from(fixture + (field to " ")) }
        }
    }

    @Test
    fun `falla con otra version del contrato`() {
        assertThrows<InvalidMessageException> { RuleRequest.from(fixture + ("schemaVersion" to "2")) }
        assertThrows<InvalidMessageException> { RuleRequest.from(fixture - "schemaVersion") }
    }
}
