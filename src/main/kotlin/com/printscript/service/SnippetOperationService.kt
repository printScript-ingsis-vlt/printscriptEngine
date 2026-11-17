package com.printscript.service

import com.printscript.domain.OutputComparator
import com.printscript.domain.errors.ExecutionTimeoutException
import com.printscript.domain.interactive.ExecutionHandle
import com.printscript.domain.interactive.ExecutionListener
import com.printscript.domain.interfaces.PrintScriptEngine
import com.printscript.domain.result.FormatOperationResult
import com.printscript.domain.result.FormatResult
import com.printscript.domain.result.InterpretResult
import com.printscript.domain.result.LintResult
import com.printscript.domain.result.TestRunResult
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

    // Logica pura sin estado: no hace falta que sea un bean.
    private val comparator = OutputComparator()

    fun interpret(snippetKey: String, version: String, inputs: List<String>?): InterpretResult =
        engine.interpret(files.get(snippetKey), version, inputs)

    /**
     * Arranca una ejecucion interactiva del snippet guardado. El codigo se lee antes de arrancar, asi que un
     * snippet inexistente o el storage caido salen como excepcion aca y no como evento del listener.
     */
    fun startExecution(snippetKey: String, version: String, listener: ExecutionListener): ExecutionHandle =
        engine.interpretInteractive(files.get(snippetKey), version, listener)

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

    /**
     * Valida codigo que puede venir de dos lados, y tiene que ser exactamente uno:
     * - [content]: el texto directo. No toca el Asset Service, asi que sirve para validar antes de guardar.
     * - [snippetKey]: un snippet ya guardado, que se lee del Asset Service.
     * Mandar los dos o ninguno es IllegalArgumentException (400). Un snippetKey en blanco cuenta como ausente.
     */
    fun validate(snippetKey: String?, content: String?, version: String): ValidationResult {
        val hasKey = !snippetKey.isNullOrBlank()
        require(hasKey != (content != null)) { "Mandar snippetKey o content, uno solo de los dos" }
        val code = content ?: files.get(snippetKey!!)
        return engine.validate(code, version)
    }

    /**
     * Corre un test: ejecuta el snippet con [inputs] (en orden) y compara cada println contra [expectedOutputs].
     * Que el codigo del usuario falle o tarde demasiado es un resultado del test (no pasa, con el motivo en
     * errors), no un error del servicio. Si el snippet no existe o el storage esta caido, propaga como el resto.
     *
     * Solo se compara lo que imprime el programa: el prompt de readInput("...") no cuenta como output.
     */
    fun runTest(
        snippetKey: String,
        version: String,
        inputs: List<String>,
        expectedOutputs: List<String>,
    ): TestRunResult {
        val code = files.get(snippetKey)
        val execution = try {
            engine.interpretForTest(code, version, inputs)
        } catch (e: ExecutionTimeoutException) {
            InterpretResult(output = emptyList(), errors = listOf(e.message.orEmpty()))
        }
        return comparator.compare(expectedOutputs, execution.output, execution.errors)
    }

    private fun formattedKeyOf(snippetKey: String): String = "$snippetKey$FORMATTED_SUFFIX"

    /** Sin key de reglas se usan los defaults del engine (config vacia). */
    private fun loadRules(rulesKey: String?): String? =
        if (rulesKey.isNullOrBlank()) null else files.get(rulesKey)

    companion object {
        const val FORMATTED_SUFFIX = ".formatted"
    }
}
