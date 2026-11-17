package com.printscript.domain

import ast.PrintScriptVersion
import ast.Program
import com.printscript.domain.errors.ExecutionTimeoutException
import com.printscript.domain.errors.InvalidFormatConfigException
import com.printscript.domain.errors.InvalidLintConfigException
import com.printscript.domain.errors.UnsupportedVersionException
import com.printscript.domain.interactive.ExecutionHandle
import com.printscript.domain.interactive.ExecutionListener
import com.printscript.domain.interactive.InteractiveExecution
import com.printscript.domain.interfaces.PrintScriptEngine
import com.printscript.domain.result.FormatResult
import com.printscript.domain.result.InterpretResult
import com.printscript.domain.result.LintIssue
import com.printscript.domain.result.LintResult
import com.printscript.domain.result.ValidationError
import com.printscript.domain.result.ValidationResult
import formatter.FormatterConfigurations
import formatter.FormattingRules
import formatter.PrintScriptFormatter
import interpreter.ConfigurableInterpreter
import interpreter.InterpreterConfigurations
import interpreter.Output
import interpreter.handlers.expressions.ReadInputExpressionHandler
import interpreter.ScriptInputProvider
import interpreter.MockEnvProvider
import lexer.LexerConfigurations
import lexer.StreamLexer
import linter.LinterConfigurations
import linter.PrintScriptLinter
import linter.config.IdentifierFormat
import linter.config.LintConfig
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
 import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import parser.streamRules.StreamGrammarConfigurations
import pipeline.PipelineResult
import pipeline.StreamPipeline
import result.CompilerError
import result.LexicalError
import result.Result
import result.SemanticError
import result.SyntaxError
import semantic.SemanticAnalyzer
import semantic.SemanticConfigurations
import token.Token
import java.io.StringReader
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// aca guardo toda la logica del lenguaje
// executionTimeout: cuanto puede tardar un interpret antes de cortarlo (engine.execution-timeout).
// inputTimeout: en una ejecucion interactiva, cuanto se espera cada input (engine.input-timeout).
@Component
class DefaultPrintScriptEngine(
    @Value("\${engine.execution-timeout:10s}") private val executionTimeout: Duration = DEFAULT_TIMEOUT,
    @Value("\${engine.input-timeout:5m}") private val inputTimeout: Duration = DEFAULT_INPUT_TIMEOUT,
) : PrintScriptEngine {

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val DEFAULT_INPUT_TIMEOUT: Duration = Duration.ofMinutes(5)
        const val MAX_OUTPUT_LINES = 10_000
    }

    // Hilos daemon: si una ejecucion no responde al interrupt, no bloquea el apagado del servicio.
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "printscript-run").apply { isDaemon = true }
    }

    // Controla el timeout de las ejecuciones interactivas (que no pueden usar future.get con limite).
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "printscript-watchdog").apply { isDaemon = true }
    }

    // ---------------------------------------------------------------- interpret

    override fun interpret(code: String, version: String, inputs: List<String>?): InterpretResult =
        runWithTimeout(code, version, inputs, echoPrompts = true)

    override fun interpretForTest(code: String, version: String, inputs: List<String>): InterpretResult =
        runWithTimeout(code, version, inputs, echoPrompts = false)

    /**
     * Corre el programa en el executor con [executionTimeout]. Cada forma de terminar del Future
     * (timeout, interrupcion, fallo) se traduce a su excepcion.
     * Con [echoPrompts] en false, el prompt de readInput no se escribe en el output.
     */
    @Suppress("ThrowsCount")
    private fun runWithTimeout(
        code: String,
        version: String,
        inputs: List<String>?,
        echoPrompts: Boolean,
    ): InterpretResult {
        val psVersion = parseVersion(version)
        val output = mutableListOf<String>()
        val inputIterator = (inputs ?: emptyList()).iterator()

        // Output y ScriptInputProvider son interfaces Kotlin comunes (no "fun interface"),
        // por eso se implementan con object en vez de lambda.
        val out = object : Output {
            override fun write(text: String) {
                check(output.size < MAX_OUTPUT_LINES) {
                    "Se supero el maximo de $MAX_OUTPUT_LINES lineas de salida"
                }
                output.add(text)
            }
        }
        val input = object : ScriptInputProvider {
            override fun readLine(): String {
                check(inputIterator.hasNext()) { "readInput sin entrada disponible" }
                return inputIterator.next()
            }
        }
        val future = executor.submit<InterpretResult> {
            val errors = if (echoPrompts) {
                runPipeline(code, psVersion, out, input)
            } else {
                runSilentPrompts(code, psVersion, out, input)
            }
            InterpretResult(output, errors)
        }
        try {
            return future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw ExecutionTimeoutException(executionTimeout, e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Ejecucion interrumpida", e)
        } catch (e: ExecutionException) {
            throw IllegalStateException("Fallo inesperado al ejecutar", e)
        }
    }

    /**
     * Como [runPipeline], pero readInput no escribe su prompt en [out] (ver [SilentPromptReadInputHandler]).
     * StreamPipeline no deja cambiar los handlers del interprete, por eso aca se arma a mano:
     * se valida el programa entero (lexer + parser + semantico) y despues se ejecuta.
     */
    private fun runSilentPrompts(
        code: String,
        version: PrintScriptVersion,
        out: Output,
        input: ScriptInputProvider,
    ): List<String> {
        val validationErrors = mutableListOf<ValidationError>()
        val parsed = parse(code, version, validationErrors, semanticCheck = true)
            ?: return validationErrors.map { it.message }

        val base = InterpreterConfigurations.getConfiguration(version)
        val configuration = base.copy(
            expressionHandlers = base.expressionHandlers.map {
                if (it is ReadInputExpressionHandler) SilentPromptReadInputHandler(it) else it
            },
        )
        return try {
            // Sin variables de entorno, igual que runPipeline.
            ConfigurableInterpreter(out, configuration, input, MockEnvProvider(emptyMap())).run(parsed.program)
            emptyList()
        } catch (
            // Los errores de ejecucion del programa del usuario llegan como excepciones de cualquier tipo.
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            listOf(messageOf(e))
        }
    }

    // ---------------------------------------------------- interpret interactivo

    /**
     * Mismo pipeline que [interpret], pero el output va saliendo por [listener] y los inputs llegan de a uno.
     * El timeout descuenta el tiempo esperando input (ver [InteractiveExecution]).
     */
    override fun interpretInteractive(code: String, version: String, listener: ExecutionListener): ExecutionHandle {
        val psVersion = parseVersion(version)
        val execution = InteractiveExecution(listener, executionTimeout, inputTimeout, MAX_OUTPUT_LINES)
        execution.start(executor, watchdog) { out, input -> runPipeline(code, psVersion, out, input) }
        return execution
    }

    /** Corre lexer, parser, semantico e interprete. Devuelve los errores; el output sale por [out]. */
    private fun runPipeline(
        code: String,
        version: PrintScriptVersion,
        out: Output,
        input: ScriptInputProvider,
    ): List<String> {
        val errors = mutableListOf<String>()
        try {
            // Sin variables de entorno: SystemEnvProvider le expondria al programa del usuario las del
            // contenedor (DB_PASSWORD, tokens, etc.). Un readEnv devuelve error en vez de un secreto.
            val pipeline = StreamPipeline(version, out, input, MockEnvProvider(emptyMap()))

            when (val result = pipeline.run(StringReader(code))) {
                is PipelineResult.LexicalFailure -> toCodeErrors(result.error).mapTo(errors) { it.message }
                is PipelineResult.SyntaxFailure -> toCodeErrors(result.error).mapTo(errors) { it.message }
                is PipelineResult.SemanticFailure -> toCodeErrors(result.errors).mapTo(errors) { it.message }
                else -> Unit
            }
        } catch (
            // Los errores de ejecucion del programa del usuario llegan como excepciones de cualquier tipo.
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            errors.add(messageOf(e))
        }
        return errors
    }

    // -------------------------------------------------------------------- lint

    /**
     * issues = el snippet no cumple las reglas; errors = el codigo no compila.
     * Una config invalida lanza InvalidLintConfigException (400) y un fallo inesperado
     * del linter sale como 500: ninguno de los dos se mezcla con issues/errors.
     */
    override fun lint(code: String, version: String, lintConfigJson: String?): LintResult {
        val psVersion = parseVersion(version)
        val json = toConfigJson(lintConfigJson, ::InvalidLintConfigException)
        val lintConfig = parseLintConfig(json)
        val identifierFormatRequested = json.has("identifier-format")

        val errors = mutableListOf<ValidationError>()
        val parsed = parse(code, psVersion, errors, semanticCheck = true)
            ?: return LintResult(emptyList(), errors.map { it.message })

        val issues = PrintScriptLinter(lintConfig, LinterConfigurations.getConfiguration(psVersion))
            .lint(parsed.program)
            .filter { identifierFormatRequested || it.rule != "identifier-format" }
            .map {
                LintIssue(
                    rule = it.rule,
                    severity = it.severity.toString(),
                    message = it.message,
                    line = it.position.line,
                    column = it.position.column,
                )
            }
        return LintResult(issues, emptyList())
    }

    // ------------------------------------------------------------------ format

    /** Igual criterio que lint: config invalida -> InvalidFormatConfigException (400), fallo inesperado -> 500. */
    override fun format(code: String, version: String, formatConfigJson: String?): FormatResult {
        val psVersion = parseVersion(version)
        val rules = parseFormattingRules(
            toConfigJson(formatConfigJson, ::InvalidFormatConfigException),
        )

        val errors = mutableListOf<ValidationError>()
        // El formatter original no hacia chequeo semantico; se mantiene asi.
        val parsed = parse(code, psVersion, errors, semanticCheck = false)
            ?: return FormatResult(null, errors.map { it.message })

        val formatter = PrintScriptFormatter(rules, FormatterConfigurations.getConfiguration(psVersion))
        return FormatResult(formatter.format(parsed.tokens, parsed.program), emptyList())
    }

    // ---------------------------------------------------------------- validate

    /** Devuelve los errores lexicos, sintacticos y semanticos con su regla, linea y columna. */
    override fun validate(code: String, version: String): ValidationResult {
        val psVersion = parseVersion(version)
        val errors = mutableListOf<ValidationError>()
        parse(code, psVersion, errors, semanticCheck = true)
        return ValidationResult(errors)
    }

    // ----------------------------------------------------------------- helpers

    private data class Parsed(val tokens: List<Token>, val program: Program)

    /** Lexer + parser (+ semantico opcional). Devuelve null y completa [errors] si algo falla. */
    @Suppress("UNCHECKED_CAST")
    private fun parse(
        code: String,
        version: PrintScriptVersion,
        errors: MutableList<ValidationError>,
        semanticCheck: Boolean,
    ): Parsed? {
        val lexerConfig = LexerConfigurations.getConfiguration(version)
        val tokens = when (val tokenResult = StreamLexer(StringReader(code), lexerConfig).tokenize()) {
            is Result.Failure<*> -> {
                errors += toCodeErrors(tokenResult.error)
                return null
            }
            is Result.Success<*> -> tokenResult.value as List<Token>
        }

        val parser = StreamGrammarConfigurations.getConfiguration(version)
        val program = when (val parseResult = parser.parse(tokens)) {
            is Result.Failure<*> -> {
                errors += toCodeErrors(parseResult.error)
                return null
            }
            is Result.Success<*> -> parseResult.value as Program
        }

        if (semanticCheck) {
            val semanticConfig = SemanticConfigurations.getConfiguration(version)
            val semanticErrors = SemanticAnalyzer(semanticConfig).analyze(program)
            if (semanticErrors.isNotEmpty()) {
                errors += toCodeErrors(semanticErrors)
                return null
            }
        }
        return Parsed(tokens, program)
    }

    private fun parseVersion(version: String): PrintScriptVersion =
        try {
            PrintScriptVersion.fromString(version)
        } catch (e: IllegalArgumentException) {
            throw UnsupportedVersionException(version, e)
        }

    /**
     * Parsea el JSON de reglas. Si esta mal formado lanza la excepcion que arma [invalid],
     * asi un error de configuracion nunca se confunde con un error del snippet.
     */
    private fun toConfigJson(json: String?, invalid: (String, Throwable) -> RuntimeException): JSONObject =
        try {
            if (json.isNullOrBlank()) JSONObject() else JSONObject(JSONTokener(json))
        } catch (e: JSONException) {
            throw invalid("Las reglas no son un JSON valido: ${e.message}", e)
        }

    private fun parseFormattingRules(json: JSONObject): FormattingRules =
        try {
            buildFormattingRules(json)
        } catch (e: JSONException) {
            throw InvalidFormatConfigException("Reglas de formato invalidas: ${e.message}", e)
        }

    private fun buildFormattingRules(json: JSONObject): FormattingRules {
        val forceSingleSpace = json.optBoolean("mandatory-single-space-separation", false)
        val forced: Boolean? = if (forceSingleSpace) true else null

        val spaceBeforeColon = json.optBooleanOrNull("enforce-spacing-before-colon-in-declaration") ?: forced
        val spaceAfterColon = json.optBooleanOrNull("enforce-spacing-after-colon-in-declaration") ?: forced

        val spaceAroundEqual = when {
            json.optBoolean("enforce-spacing-around-equals", false) || forceSingleSpace -> true
            json.optBoolean("enforce-no-spacing-around-equals", false) -> false
            else -> null
        }

        val newlinesBeforePrintln = json.optIntOrNull("line-breaks-after-println")
        val indentationSpaces = json.optIntOrNull("indent-inside-if")
        val spaceAroundPrintParens = forced

        val ifBraceOnNewLine = when {
            json.optBoolean("if-brace-below-line", false) -> true
            json.optBoolean("if-brace-same-line", false) -> false
            else -> null
        }

        return FormattingRules(
            spaceBeforeColon, spaceAfterColon, spaceAroundEqual, newlinesBeforePrintln,
            indentationSpaces, spaceAroundPrintParens, ifBraceOnNewLine,
        )
    }

    private fun parseLintConfig(json: JSONObject): LintConfig =
        try {
            buildLintConfig(json)
        } catch (e: JSONException) {
            throw InvalidLintConfigException("Reglas de lint invalidas: ${e.message}", e)
        }

    private fun buildLintConfig(json: JSONObject): LintConfig {
        val identifierFormat =
            if (json.has("identifier-format")) {
                when (val value = json.getString("identifier-format")) {
                    "camel-case" -> IdentifierFormat.CAMEL_CASE
                    "snake-case" -> IdentifierFormat.SNAKE_CASE
                    else -> throw InvalidLintConfigException(
                        "Valor invalido para 'identifier-format': '$value'. " +
                            "Valores permitidos: 'camel-case', 'snake-case'.",
                    )
                }
            } else {
                IdentifierFormat.CAMEL_CASE
            }

        return LintConfig(
            identifierFormat,
            json.optBoolean("mandatory-variable-or-literal-in-println", false),
            json.optBoolean("mandatory-variable-or-literal-in-readInput", false),
        )
    }

    /** Convierte los errores del compilador en ValidationError con regla y posicion. */
    private fun toCodeErrors(error: Any?): List<ValidationError> =
        when (error) {
            is CompilerError -> listOf(
                ValidationError(ruleOf(error), error.message, error.position.line, error.position.column),
            )
            is List<*> -> error.flatMap { toCodeErrors(it) }
            else -> listOf(ValidationError("unknown", error.toString(), null, null))
        }

    private fun ruleOf(error: CompilerError): String =
        when (error) {
            is LexicalError -> "lexical"
            is SyntaxError -> "syntax"
            is SemanticError -> "semantic"
        }

    private fun messageOf(e: Throwable): String = e.message ?: e.toString()

    // Igual que json.getBoolean/getInt de antes: si la clave existe pero el tipo es invalido, lanza.
    private fun JSONObject.optBooleanOrNull(key: String): Boolean? = if (has(key)) getBoolean(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key)) getInt(key) else null
}
