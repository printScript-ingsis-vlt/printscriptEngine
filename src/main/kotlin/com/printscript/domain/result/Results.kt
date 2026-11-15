package com.printscript.domain.result

import com.fasterxml.jackson.annotation.JsonPropertyOrder

// success() es funcion (no propiedad) para que Jackson no la agregue al JSON,
// igual que pasaba con los records de Java.

data class InterpretResult(val output: List<String>, val errors: List<String>) {
    fun success(): Boolean = errors.isEmpty()
}

data class LintIssue(
    val rule: String,
    val severity: String,
    val message: String,
    val line: Int,
    val column: Int,
)

data class LintResult(val issues: List<LintIssue>, val errors: List<String>) {
    fun success(): Boolean = issues.isEmpty() && errors.isEmpty()
}

data class FormatResult(val formatted: String?, val errors: List<String>) {
    fun success(): Boolean = errors.isEmpty() && formatted != null
}

/**
 * Respuesta de /format. El codigo formateado se guarda en formattedKey sin pisar snippetKey.
 * Si el snippet no compila no se escribe nada: formattedKey es null y el motivo va en errors.
 */
data class FormatOperationResult(val snippetKey: String, val formattedKey: String?, val errors: List<String>)

/**
 * Error del codigo del usuario con su posicion.
 * rule sale del tipo de error: "lexical", "syntax" o "semantic".
 */
data class ValidationError(val rule: String, val message: String, val line: Int?, val column: Int?)

/** valid es una propiedad calculada para que salga en el JSON: { "valid": false, "errors": [...] }. */
@JsonPropertyOrder("valid", "errors")
data class ValidationResult(val errors: List<ValidationError>) {
    val valid: Boolean
        get() = errors.isEmpty()
}

enum class LineStatus { PASS, FAIL }
data class OutputLineResult(val index: Int, val expected: String?, val actual: String?, val status: LineStatus)
data class TestRunResult(
    val passed: Boolean, val total: Int, val passedCount: Int, val failedCount: Int,
    val lines: List<OutputLineResult>, val errors: List<String>,
)
