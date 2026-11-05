package com.printscript.domain.result

// success()/valid() son funciones (no propiedades) para que Jackson no las agregue al JSON,
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

data class ValidationError(val message: String, val line: Int?, val column: Int?)

data class ValidationResult(val errors: List<ValidationError>) {
    fun valid(): Boolean = errors.isEmpty()
}
