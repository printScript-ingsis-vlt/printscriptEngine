package com.printscript.messaging

import com.printscript.domain.result.LintIssue

/** Estado de cumplimiento que snippet-service guarda en el snippet (enum Compliance de ese servicio). */
enum class LintStatus { COMPLIANT, NOT_COMPLIANT, FAILED }

enum class FormatStatus { FORMATTED, FAILED }

/**
 * Respuesta a un pedido de lint.
 * COMPLIANT: sin issues. NOT_COMPLIANT: hay issues. FAILED: el codigo no compila o el pedido no se pudo procesar.
 */
data class LintResultMessage(
    val requestId: String,
    val snippetId: String,
    val status: LintStatus,
    val issues: List<LintIssue> = emptyList(),
    val errors: List<String> = emptyList(),
)

/**
 * Respuesta a un pedido de format. El codigo formateado viaja en [formatted] y lo guarda snippet-service:
 * en este flujo el engine no escribe nada en el Asset Service.
 */
data class FormatResultMessage(
    val requestId: String,
    val snippetId: String,
    val status: FormatStatus,
    val formatted: String? = null,
    val errors: List<String> = emptyList(),
)
