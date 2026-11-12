package com.printscript.service

import com.printscript.domain.interfaces.PrintScriptEngine
import com.printscript.domain.result.FormatOperationResult
import com.printscript.domain.result.FormatResult
import com.printscript.domain.result.InterpretResult
import com.printscript.domain.result.LintResult
import com.printscript.domain.result.ValidationResult
import com.printscript.repository.FileRepository
import org.springframework.stereotype.Service

/**
 * Orquesta: busca los datos en el repositorio y delega la logica del lenguaje al engine.
 * Los permisos los valida el modulo de snippets antes de llamar a este servicio.
 *
 * Excepciones que salen de aca (las mapea el controller):
 * - StorageFileNotFoundException -> 404
 * - StorageUnavailableException  -> 502/503
 * - IllegalArgumentException     -> 400 (version invalida)
 * - ExecutionTimeoutException    -> 408/504
 */
@Service
class SnippetOperationService(
    private val files: FileRepository,
    private val engine: PrintScriptEngine,
) {

    fun interpret(snippetKey: String, version: String, inputs: List<String>?): InterpretResult =
        engine.interpret(files.get(snippetKey), version, inputs)

    fun lint(snippetKey: String, rulesKey: String?, version: String): LintResult =
        engine.lint(files.get(snippetKey), version, loadRules(rulesKey))

    /**
     * Formatea el snippet y guarda el resultado en "{snippetKey}.formatted". Nunca escribe snippetKey:
     * la original se puede seguir descargando y no hay carrera con las ediciones del usuario.
     */
    fun format(snippetKey: String, rulesKey: String?, version: String): FormatOperationResult {
        val result = formatContent(snippetKey, rulesKey, version)
        val formatted = result.formatted
            ?: return FormatOperationResult(snippetKey, null, result.errors)

        val formattedKey = formattedKeyOf(snippetKey)
        files.put(formattedKey, formatted)
        return FormatOperationResult(snippetKey, formattedKey, result.errors)
    }

    /**
     * Formatea sin guardar nada: devuelve el codigo formateado. Lo usa el flujo por Redis,
     * donde quien persiste el resultado es snippet-service.
     */
    fun formatContent(snippetKey: String, rulesKey: String?, version: String): FormatResult =
        engine.format(files.get(snippetKey), version, loadRules(rulesKey))

    fun validate(snippetKey: String, version: String): ValidationResult =
        engine.validate(files.get(snippetKey), version)

    private fun formattedKeyOf(snippetKey: String): String = "$snippetKey$FORMATTED_SUFFIX"

    /** Sin key de reglas se usan los defaults del engine (config vacia). */
    private fun loadRules(rulesKey: String?): String? =
        if (rulesKey.isNullOrBlank()) null else files.get(rulesKey)

    companion object {
        const val FORMATTED_SUFFIX = ".formatted"
    }
}
