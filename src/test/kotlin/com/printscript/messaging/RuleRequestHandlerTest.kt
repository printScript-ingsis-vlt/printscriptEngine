package com.printscript.messaging

import com.printscript.domain.DefaultPrintScriptEngine
import com.printscript.fakes.InMemoryFileRepository
import com.printscript.service.SnippetOperationService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleRequestHandlerTest {

    private class RecordingPublisher : ResultPublisher {
        val lints = mutableListOf<LintResultMessage>()
        val formats = mutableListOf<FormatResultMessage>()
        val deadLetters = mutableListOf<Pair<RuleOperation, Map<String, String>>>()
        var failing = false

        override fun publishLint(result: LintResultMessage) {
            check(!failing) { "Redis caido" }
            lints += result
        }

        override fun publishFormat(result: FormatResultMessage) {
            check(!failing) { "Redis caido" }
            formats += result
        }

        override fun deadLetter(operation: RuleOperation, fields: Map<String, String>, reason: String) {
            deadLetters += operation to (fields + ("reason" to reason))
        }
    }

    private val files = InMemoryFileRepository("snippet" to "let x: number = 5;")
    private val publisher = RecordingPublisher()
    private val handler = RuleRequestHandler(
        RuleRequestProcessor(SnippetOperationService(files, DefaultPrintScriptEngine())),
        publisher,
    )

    private val fields = mapOf(
        "schemaVersion" to "1",
        "requestId" to "req-1",
        "snippetId" to "snip-1",
        "snippetKey" to "snippet",
        "language" to "printscript",
        "version" to "1.0",
    )

    @Test
    fun `lint publica la respuesta y pide ack`() {
        assertTrue(handler.handle(RuleOperation.LINT, fields))

        assertEquals(LintStatus.COMPLIANT, publisher.lints.single().status)
        assertTrue(publisher.formats.isEmpty())
    }

    @Test
    fun `format publica la respuesta con el codigo formateado`() {
        assertTrue(handler.handle(RuleOperation.FORMAT, fields))

        val result = publisher.formats.single()
        assertEquals(FormatStatus.FORMATTED, result.status)
        assertEquals("req-1", result.requestId)
    }

    @Test
    fun `un mensaje invalido va a la DLQ, se responde FAILED y se hace ack`() {
        assertTrue(handler.handle(RuleOperation.LINT, fields - "version"))

        assertEquals(RuleOperation.LINT, publisher.deadLetters.single().first)
        assertTrue(publisher.deadLetters.single().second.getValue("reason").contains("version"))
        assertEquals(LintStatus.FAILED, publisher.lints.single().status)
    }

    @Test
    fun `sin requestId solo va a la DLQ porque no hay a quien responder`() {
        assertTrue(handler.handle(RuleOperation.FORMAT, fields - "requestId"))

        assertEquals(1, publisher.deadLetters.size)
        assertTrue(publisher.formats.isEmpty())
    }

    @Test
    fun `error transitorio no pide ack para que se reintente`() {
        files.unavailable = true

        assertFalse(handler.handle(RuleOperation.LINT, fields))
        assertTrue(publisher.lints.isEmpty())
        assertTrue(publisher.deadLetters.isEmpty())
    }

    @Test
    fun `si no se puede publicar la respuesta tampoco hay ack`() {
        publisher.failing = true

        assertFalse(handler.handle(RuleOperation.LINT, fields))
    }

    @Test
    fun `agotados los reintentos va a la DLQ y responde FAILED`() {
        handler.handleExhausted(RuleOperation.FORMAT, fields, "max deliveries (3) exceeded")

        assertEquals("max deliveries (3) exceeded", publisher.deadLetters.single().second["reason"])
        val result = publisher.formats.single()
        assertEquals(FormatStatus.FAILED, result.status)
        assertEquals(listOf("max deliveries (3) exceeded"), result.errors)
    }
}
