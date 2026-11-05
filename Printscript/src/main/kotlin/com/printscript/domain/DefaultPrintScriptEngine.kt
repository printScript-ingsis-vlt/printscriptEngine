package com.printscript.domain

import ast.PrintScriptVersion
import ast.Program
import com.printscript.domain.errors.ExecutionTimeoutException
import com.printscript.domain.errors.InvalidLintConfigException
import com.printscript.domain.errors.UnsupportedVersionException
import com.printscript.domain.interfaces.PrintScriptEngine
import com.printscript.domain.result.FormatResult
import com.printscript.domain.result.InterpretResult
import com.printscript.domain.result.LintIssue
import com.printscript.domain.result.LintResult
import com.printscript.domain.result.ValidationError
import com.printscript.domain.result.ValidationResult
import formatter.FormattingRules
import formatter.PrintScriptFormatter
import interpreter.Output
import interpreter.ScriptInputProvider
import interpreter.SystemEnvProvider
import lexer.LexerConfigurations
import lexer.StreamLexer
import linter.PrintScriptLinter
import linter.config.IdentifierFormat
import linter.config.LintConfig
import org.json.JSONObject
import org.json.JSONTokener
import org.springframework.stereotype.Component
import parser.streamRules.StreamGrammarConfigurations
import pipeline.PipelineResult
import pipeline.StreamPipeline
import result.CompilerError
import result.Result
import semantic.SemanticAnalyzer
import semantic.SemanticConfigurations
import token.Token
import java.io.StringReader
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// aca guardo toda la logica del lenguaje
@Component
class DefaultPrintScriptEngine : PrintScriptEngine {

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val MAX_OUTPUT_LINES = 10_000
    }

    // Hilos daemon: si una ejecucion no responde al interrupt, no bloquea el apagado del servicio.
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "printscript-run").apply { isDaemon = true }
    }

    // ---------------------------------------------------------------- interpret

    override fun interpret(code: String, version: String, inputs: List<String>?): InterpretResult {
        val psVersion = parseVersion(version)
        val future = executor.submit<InterpretResult> { runInterpreter(code, psVersion, inputs) }
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw ExecutionTimeoutException(TIMEOUT_SECONDS)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Ejecucion interrumpida", e)
        } catch (e: ExecutionException) {
            throw IllegalStateException("Fallo inesperado al ejecutar", e.cause)
        }
    }

    private fun runInterpreter(code: String, version: PrintScriptVersion, inputs: List<String>?): InterpretResult {
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val inputIterator = (inputs ?: emptyList()).iterator()

        try {
            // Output y ScriptInputProvider son interfaces Kotlin comunes (no "fun interface"),
            // por eso se implementan con object en vez de lambda.
            val out = object : Output {
                override fun write(line: String) {
                    check(output.size < MAX_OUTPUT_LINES) {
                        "Se supero el maximo de $MAX_OUTPUT_LINES lineas de salida"
                    }
                    output.add(line)
                }
            }
            val input = object : ScriptInputProvider {
                override fun readLine(): String {
                    check(inputIterator.hasNext()) { "readInput sin entrada disponible" }
                    return inputIterator.next()
                }
            }

            // TODO: SystemEnvProvider expone las variables de entorno del contenedor (DB_PASSWORD, etc.)
            // al programa del usuario. Reemplazarlo por un provider propio que devuelva un mapa vacio.
            val pipeline = StreamPipeline(version, out, input, SystemEnvProvider())

            when (val result = pipeline.run(StringReader(code))) {
                is PipelineResult.LexicalFailure -> reportError(result.error, errors)
                is PipelineResult.SyntaxFailure -> reportError(result.error, errors)
                is PipelineResult.SemanticFailure -> result.errors.forEach { reportError(it, errors) }
                else -> Unit
            }
        } catch (e: Throwable) {
            errors.add(messageOf(e))
        }
        return InterpretResult(output, errors)
    }

    // -------------------------------------------------------------------- lint

    override fun lint(code: String, version: String, lintConfigJson: String?): LintResult {
        val psVersion = parseVersion(version)
        val issues = mutableListOf<LintIssue>()
        val errors = mutableListOf<String>()

        try {
            val parsed = parse(code, psVersion, errors, semanticCheck = true)
                ?: return LintResult(issues, errors)

            val json = toJson(lintConfigJson)
            val lintConfig = parseLintConfig(json)
            val identifierFormatRequested = json.has("identifier-format")

            PrintScriptLinter(lintConfig).lint(parsed.program)
                .filter { identifierFormatRequested || it.rule != "identifier-format" }
                .mapTo(issues) {
                    LintIssue(
                        rule = it.rule,
                        severity = it.severity.toString(),
                        message = it.message,
                        line = it.position.line,
                        column = it.position.column,
                    )
                }
        } catch (e: InvalidLintConfigException) {
            throw e
        } catch (e: Exception) {
            errors.add(messageOf(e))
        }

        return LintResult(issues, errors)
    }

    // ------------------------------------------------------------------ format

    override fun format(code: String, version: String, formatConfigJson: String?): FormatResult {
        val psVersion = parseVersion(version)
        val errors = mutableListOf<String>()
        return try {
            // El formatter original no hacia chequeo semantico; se mantiene asi.
            val parsed = parse(code, psVersion, errors, semanticCheck = false)
                ?: return FormatResult(null, errors)
            val rules = parseFormattingRules(toJson(formatConfigJson))
            val formatted = PrintScriptFormatter(rules).format(parsed.tokens, parsed.program)
            FormatResult(formatted, errors)
        } catch (e: Exception) {
            errors.add(messageOf(e))
            FormatResult(null, errors)
        }
    }

    // ---------------------------------------------------------------- validate

    override fun validate(code: String, version: String): ValidationResult {
        val psVersion = parseVersion(version)
        val errors = mutableListOf<String>()

        try {
            parse(code, psVersion, errors, semanticCheck = true)
        } catch (e: Exception) {
            errors.add(messageOf(e))
        }

        return ValidationResult(errors.map { ValidationError(it, null, null) })
    }

    // ----------------------------------------------------------------- helpers

    private data class Parsed(val tokens: List<Token>, val program: Program)

    /** Lexer + parser (+ semantico opcional). Devuelve null y completa [errors] si algo falla. */
    @Suppress("UNCHECKED_CAST")
    private fun parse(
        code: String,
        version: PrintScriptVersion,
        errors: MutableList<String>,
        semanticCheck: Boolean,
    ): Parsed? {
        val lexerConfig = LexerConfigurations.getConfiguration(version)
        val tokens = when (val tokenResult = StreamLexer(StringReader(code), lexerConfig).tokenize()) {
            is Result.Failure<*> -> {
                reportError(tokenResult.error, errors)
                return null
            }
            is Result.Success<*> -> tokenResult.value as List<Token>
        }

        val parser = StreamGrammarConfigurations.getConfiguration(version)
        val program = when (val parseResult = parser.parse(tokens)) {
            is Result.Failure<*> -> {
                reportError(parseResult.error, errors)
                return null
            }
            is Result.Success<*> -> parseResult.value as Program
        }

        if (semanticCheck) {
            val semanticConfig = SemanticConfigurations.getConfiguration(version)
            val semanticErrors = SemanticAnalyzer(semanticConfig).analyze(program)
            if (semanticErrors.isNotEmpty()) {
                semanticErrors.forEach { reportError(it, errors) }
                return null
            }
        }
        return Parsed(tokens, program)
    }

    private fun parseVersion(version: String): PrintScriptVersion =
        try {
            PrintScriptVersion.fromString(version) ?: throw UnsupportedVersionException(version)
        } catch (e: UnsupportedVersionException) {
            throw e
        } catch (e: Exception) {
            throw UnsupportedVersionException(version)
        }

    private fun toJson(json: String?): JSONObject =
        if (json.isNullOrBlank()) JSONObject() else JSONObject(JSONTokener(json))

    private fun parseFormattingRules(json: JSONObject): FormattingRules {
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

    private fun parseLintConfig(json: JSONObject): LintConfig {
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

    private fun reportError(error: Any?, errors: MutableList<String>) {
        when (error) {
            is CompilerError -> errors.add(error.message)
            is List<*> -> error.forEach { reportError(it, errors) }
            else -> errors.add(error.toString())
        }
    }

    private fun messageOf(e: Throwable): String = e.message ?: e.toString()

    // Igual que json.getBoolean/getInt de antes: si la clave existe pero el tipo es invalido, lanza.
    private fun JSONObject.optBooleanOrNull(key: String): Boolean? = if (has(key)) getBoolean(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key)) getInt(key) else null
}
