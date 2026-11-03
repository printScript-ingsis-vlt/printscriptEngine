package com.printscript.domain;

import ast.PrintScriptVersion;
import ast.Program;
import com.printscript.domain.errors.ExecutionTimeoutException;
import com.printscript.domain.errors.InvalidLintConfigException;
import com.printscript.domain.errors.UnsupportedVersionException;
import com.printscript.domain.interfaces.PrintScriptEngine;
import com.printscript.domain.result.*;
import formatter.FormattingRules;
import interpreter.Output;
import interpreter.ScriptInputProvider;
import interpreter.SystemEnvProvider;
import lexer.LexerConfigurations;
import lexer.StreamLexer;
import linter.config.IdentifierFormat;
import linter.config.LintConfig;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.stereotype.Component;
import parser.StreamParser;
import parser.streamRules.StreamGrammarConfigurations;
import pipeline.PipelineResult;
import pipeline.StreamPipeline;
import result.CompilerError;
import result.Result;
import semantic.SemanticAnalyzer;
import semantic.SemanticConfigurations;
import token.Token;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// aca guardo toda la logica del lenguaje
@Component
public class DefaultPrintScriptEngine implements PrintScriptEngine {

    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_OUTPUT_LINES = 10_000;

    // Hilos daemon: si una ejecucion no responde al interrupt, no bloquea el apagado del servicio.
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "printscript-run");
        thread.setDaemon(true);
        return thread;
    });

    // ---------------------------------------------------------------- interpret

    @Override
    public InterpretResult interpret(String code, String version, List<String> inputs) {
        PrintScriptVersion psVersion = parseVersion(version);
        Future<InterpretResult> future = executor.submit(() -> runInterpreter(code, psVersion, inputs));
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ExecutionTimeoutException(TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ejecucion interrumpida", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Fallo inesperado al ejecutar", e.getCause());
        }
    }

    private InterpretResult runInterpreter(String code, PrintScriptVersion version, List<String> inputs) {
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Iterator<String> inputIterator = (inputs == null ? List.<String>of() : inputs).iterator();

        try {
            Output out = line -> {
                if (output.size() >= MAX_OUTPUT_LINES) {
                    throw new IllegalStateException("Se supero el maximo de " + MAX_OUTPUT_LINES + " lineas de salida");
                }
                output.add(line);
            };
            ScriptInputProvider in = () -> {
                if (!inputIterator.hasNext()) {
                    throw new IllegalStateException("readInput sin entrada disponible");
                }
                return inputIterator.next();
            };

            // TODO: SystemEnvProvider expone las variables de entorno del contenedor (DB_PASSWORD, etc.)
            // al programa del usuario. Reemplazarlo por un provider propio que devuelva un mapa vacio.
            StreamPipeline pipeline = new StreamPipeline(version, out, in, new SystemEnvProvider());
            PipelineResult result = pipeline.run(new StringReader(code));

            if (result instanceof PipelineResult.LexicalFailure failure) {
                reportError(failure.getError(), errors);
            } else if (result instanceof PipelineResult.SyntaxFailure failure) {
                reportError(failure.getError(), errors);
            } else if (result instanceof PipelineResult.SemanticFailure failure) {
                for (Object err : failure.getErrors()) {
                    reportError(err, errors);
                }
            }
        } catch (Throwable e) {
            errors.add(messageOf(e));
        }
        return new InterpretResult(output, errors);
    }

    // -------------------------------------------------------------------- lint

    @Override
    public LintResult lint(String code, String version, String lintConfigJson) {
        PrintScriptVersion psVersion = parseVersion(version);

        List<LintIssue> issues = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            Parsed parsed = parse(code, psVersion, errors, true);

            if (parsed == null) {
                return new LintResult(issues, errors);
            }

            JSONObject json = toJson(lintConfigJson);
            LintConfig lintConfig = parseLintConfig(json);

            boolean identifierFormatRequested = json.has("identifier-format");

            var notifications =
                    new linter.PrintScriptLinter(lintConfig).lint(parsed.program());

            for (var n : notifications) {
                if (!identifierFormatRequested
                        && "identifier-format".equals(n.getRule())) {
                    continue;
                }

                issues.add(new LintIssue(
                        n.getRule(),
                        String.valueOf(n.getSeverity()),
                        n.getMessage(),
                        n.getPosition().getLine(),
                        n.getPosition().getColumn()
                ));
            }
        }  catch (InvalidLintConfigException e) {
                throw e;
    } catch (Exception e) {
        errors.add(messageOf(e));
    }

        return new LintResult(issues, errors);
    }

    // ------------------------------------------------------------------ format

    @Override
    public FormatResult format(String code, String version, String formatConfigJson) {
        PrintScriptVersion psVersion = parseVersion(version);
        List<String> errors = new ArrayList<>();
        try {
            // El formatter original no hacia chequeo semantico; se mantiene asi.
            Parsed parsed = parse(code, psVersion, errors, false);
            if (parsed == null) {
                return new FormatResult(null, errors);
            }
            FormattingRules rules = parseFormattingRules(toJson(formatConfigJson));
            String formatted = new formatter.PrintScriptFormatter(rules).format(parsed.tokens(), parsed.program());
            return new FormatResult(formatted, errors);
        } catch (Exception e) {
            errors.add(messageOf(e));
            return new FormatResult(null, errors);
        }
    }

    @Override
    public ValidationResult validate(String code, String version) {
        PrintScriptVersion psVersion = parseVersion(version);
        List<String> errors = new ArrayList<>();

        try {
            parse(code, psVersion, errors, true);
        } catch (Exception e) {
            errors.add(messageOf(e));
        }

        List<ValidationError> validationErrors = errors.stream()
                .map(message -> new ValidationError(message, null, null))
                .toList();

        return new ValidationResult(validationErrors);
    }

    // ----------------------------------------------------------------- helpers

    private record Parsed(List<Token> tokens, Program program) {}

    /** Lexer + parser (+ semantico opcional). Devuelve null y completa {@code errors} si algo falla. */
    @SuppressWarnings("unchecked")
    private Parsed parse(String code, PrintScriptVersion version, List<String> errors, boolean semanticCheck) {
        var lexerConfig = LexerConfigurations.INSTANCE.getConfiguration(version);
        var tokenResult = new StreamLexer(new StringReader(code), lexerConfig).tokenize();
        if (tokenResult instanceof Result.Failure<?> failure) {
            reportError(failure.getError(), errors);
            return null;
        }
        List<Token> tokens = (List<Token>) ((Result.Success<?>) tokenResult).getValue();

        StreamParser parser = StreamGrammarConfigurations.INSTANCE.getConfiguration(version);
        var parseResult = parser.parse(tokens);
        if (parseResult instanceof Result.Failure<?> failure) {
            reportError(failure.getError(), errors);
            return null;
        }
        Program program = (Program) ((Result.Success<?>) parseResult).getValue();

        if (semanticCheck) {
            var semanticConfig = SemanticConfigurations.INSTANCE.getConfiguration(version);
            var semanticErrors = new SemanticAnalyzer(semanticConfig).analyze(program);
            if (!semanticErrors.isEmpty()) {
                for (Object err : semanticErrors) {
                    reportError(err, errors);
                }
                return null;
            }
        }
        return new Parsed(tokens, program);
    }

    private PrintScriptVersion parseVersion(String version) {
        try {
            PrintScriptVersion parsed =
                    PrintScriptVersion.Companion.fromString(version);

            if (parsed == null) {
                throw new UnsupportedVersionException(version);
            }

            return parsed;
        } catch (UnsupportedVersionException e) {
            throw e;
        } catch (Exception e) {
            throw new UnsupportedVersionException(version);
        }
    }

    private JSONObject toJson(String json) {
        return (json == null || json.isBlank())
                ? new JSONObject()
                : new JSONObject(new JSONTokener(json));
    }

    private FormattingRules parseFormattingRules(JSONObject json) {
        boolean forceSingleSpace = json.optBoolean("mandatory-single-space-separation", false);

        Boolean spaceBeforeColon = forceSingleSpace ? Boolean.TRUE : null;
        if (json.has("enforce-spacing-before-colon-in-declaration")) {
            spaceBeforeColon = json.getBoolean("enforce-spacing-before-colon-in-declaration");
        }

        Boolean spaceAfterColon = forceSingleSpace ? Boolean.TRUE : null;
        if (json.has("enforce-spacing-after-colon-in-declaration")) {
            spaceAfterColon = json.getBoolean("enforce-spacing-after-colon-in-declaration");
        }

        Boolean spaceAroundEqual = null;
        if (json.optBoolean("enforce-spacing-around-equals", false) || forceSingleSpace) {
            spaceAroundEqual = Boolean.TRUE;
        } else if (json.optBoolean("enforce-no-spacing-around-equals", false)) {
            spaceAroundEqual = Boolean.FALSE;
        }

        Integer newlinesBeforePrintln = null;
        if (json.has("line-breaks-after-println")) {
            newlinesBeforePrintln = json.getInt("line-breaks-after-println");
        }

        Integer indentationSpaces = null;
        if (json.has("indent-inside-if")) {
            indentationSpaces = json.getInt("indent-inside-if");
        }

        Boolean spaceAroundPrintParens = forceSingleSpace ? Boolean.TRUE : null;

        Boolean ifBraceOnNewLine = null;
        if (json.optBoolean("if-brace-below-line", false)) {
            ifBraceOnNewLine = Boolean.TRUE;
        } else if (json.optBoolean("if-brace-same-line", false)) {
            ifBraceOnNewLine = Boolean.FALSE;
        }

        return new FormattingRules(
                spaceBeforeColon, spaceAfterColon, spaceAroundEqual, newlinesBeforePrintln,
                indentationSpaces, spaceAroundPrintParens, ifBraceOnNewLine);
    }

    private LintConfig parseLintConfig(JSONObject json) {
        IdentifierFormat identifierFormat = IdentifierFormat.CAMEL_CASE;

        if (json.has("identifier-format")) {
            String value = json.getString("identifier-format");

            identifierFormat = switch (value) {
                case "camel-case" -> IdentifierFormat.CAMEL_CASE;
                case "snake-case" -> IdentifierFormat.SNAKE_CASE;
                default -> throw new InvalidLintConfigException(
                        "Valor invalido para 'identifier-format': '" + value
                                + "'. Valores permitidos: 'camel-case', 'snake-case'."
                );
            };
        }

        boolean printlnArgumentCheck =
                json.optBoolean("mandatory-variable-or-literal-in-println", false);

        boolean readInputArgumentCheck =
                json.optBoolean("mandatory-variable-or-literal-in-readInput", false);

        return new LintConfig(
                identifierFormat,
                printlnArgumentCheck,
                readInputArgumentCheck
        );
    }

    private void reportError(Object error, List<String> errors) {
        if (error instanceof CompilerError compilerError) {
            errors.add(compilerError.getMessage());
        } else if (error instanceof List<?> list) {
            for (Object e : list) {
                reportError(e, errors);
            }
        } else {
            errors.add(String.valueOf(error));
        }
    }

    private String messageOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
