package com.printscript.messaging

import org.slf4j.LoggerFactory

/**
 * Procesa un mensaje de un stream de pedidos y decide si se le puede hacer ack.
 * No depende de Redis, asi que se puede probar con fakes.
 *
 * Primero publica la respuesta y despues se hace el ack. Si el engine se cae en el medio, el pedido se
 * vuelve a entregar y la respuesta sale dos veces. snippet-service la aplica una sola vez porque
 * compara el requestId.
 */
class RuleRequestHandler(
    private val processor: RuleRequestProcessor,
    private val publisher: ResultPublisher,
) {

    /** true si el mensaje quedo resuelto (hay que hacer ack); false si hay que reintentarlo. */
    fun handle(operation: RuleOperation, fields: Map<String, String>): Boolean {
        val request = try {
            RuleRequest.from(fields)
        } catch (e: InvalidMessageException) {
            log.warn("Pedido de {} invalido, va a la DLQ: {}", operation, e.message)
            discard(operation, fields, "invalid message: ${e.message}")
            return true
        }

        return try {
            when (operation) {
                RuleOperation.LINT -> publisher.publishLint(processor.lint(request))
                RuleOperation.FORMAT -> publisher.publishFormat(processor.format(request))
            }
            true
        } catch (
            // Cualquier otro fallo (Asset Service o Redis caidos, bug) se reintenta. Despues de
            // max-deliveries el reclaimer lo manda a la DLQ con handleExhausted.
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("No se pudo procesar {} {} (se reintenta): {}", operation, request.requestId, e.message)
            false
        }
    }

    /**
     * Se agotaron los reintentos: el pedido va a la DLQ y se responde FAILED, para que el snippet
     * no quede en PENDING para siempre.
     */
    fun handleExhausted(operation: RuleOperation, fields: Map<String, String>, reason: String) {
        log.error("Pedido de {} {} descartado: {}", operation, fields[Fields.REQUEST_ID], reason)
        discard(operation, fields, reason)
    }

    private fun discard(operation: RuleOperation, fields: Map<String, String>, reason: String) {
        publisher.deadLetter(operation, fields, reason)
        // Sin requestId o snippetId no hay a quien responderle.
        val requestId = fields[Fields.REQUEST_ID]?.takeIf { it.isNotBlank() } ?: return
        val snippetId = fields[Fields.SNIPPET_ID]?.takeIf { it.isNotBlank() } ?: return
        when (operation) {
            RuleOperation.LINT -> publisher.publishLint(
                LintResultMessage(requestId, snippetId, LintStatus.FAILED, errors = listOf(reason)),
            )
            RuleOperation.FORMAT -> publisher.publishFormat(
                FormatResultMessage(requestId, snippetId, FormatStatus.FAILED, errors = listOf(reason)),
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RuleRequestHandler::class.java)
    }
}
