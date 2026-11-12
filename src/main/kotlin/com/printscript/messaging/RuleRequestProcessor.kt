package com.printscript.messaging

import com.printscript.domain.errors.ExecutionTimeoutException
import com.printscript.domain.errors.InvalidFormatConfigException
import com.printscript.domain.errors.InvalidLintConfigException
import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.domain.errors.UnsupportedVersionException
import com.printscript.service.SnippetOperationService

/**
 * Convierte un pedido en su respuesta usando la misma logica que los endpoints HTTP.
 * No sabe nada de Redis.
 *
 * Errores permanentes (reglas invalidas, version no soportada, snippet borrado): se responden como FAILED,
 * porque reintentarlos daria lo mismo. Errores transitorios (Asset Service caido, fallos inesperados):
 * se propagan para que el mensaje quede sin ack y se reintente.
 */
class RuleRequestProcessor(private val service: SnippetOperationService) {

    fun lint(request: RuleRequest): LintResultMessage = failingPermanently({
        LintResultMessage(request.requestId, request.snippetId, LintStatus.FAILED, errors = listOf(it))
    }) {
        val result = service.lint(request.snippetKey, request.rulesKey, request.version)
        val status = when {
            result.errors.isNotEmpty() -> LintStatus.FAILED
            result.issues.isNotEmpty() -> LintStatus.NOT_COMPLIANT
            else -> LintStatus.COMPLIANT
        }
        LintResultMessage(request.requestId, request.snippetId, status, result.issues, result.errors)
    }

    fun format(request: RuleRequest): FormatResultMessage = failingPermanently({
        FormatResultMessage(request.requestId, request.snippetId, FormatStatus.FAILED, errors = listOf(it))
    }) {
        val result = service.formatContent(request.snippetKey, request.rulesKey, request.version)
        val formatted = result.formatted
        if (result.errors.isEmpty() && formatted != null) {
            FormatResultMessage(request.requestId, request.snippetId, FormatStatus.FORMATTED, formatted)
        } else {
            FormatResultMessage(request.requestId, request.snippetId, FormatStatus.FAILED, errors = result.errors)
        }
    }

    /** Si [block] falla con un error permanente responde [onFailure] con el mensaje; si no, propaga. */
    private inline fun <T> failingPermanently(onFailure: (String) -> T, block: () -> T): T =
        try {
            block()
        } catch (
            // Se filtra en permanentMessage: lo que no es permanente se vuelve a lanzar.
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            onFailure(permanentMessage(e))
        }

    /** Devuelve el mensaje si el error es permanente; si no, lo vuelve a lanzar. */
    private fun permanentMessage(e: RuntimeException): String = when (e) {
        is InvalidLintConfigException,
        is InvalidFormatConfigException,
        is UnsupportedVersionException,
        is StorageFileNotFoundException,
        is ExecutionTimeoutException,
        is IllegalArgumentException,
        -> e.message ?: e.javaClass.simpleName
        else -> throw e
    }
}
