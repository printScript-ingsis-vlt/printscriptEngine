package com.printscript.service;


import com.printscript.repository.FileRepository;
import com.printscript.domain.interfaces.PrintScriptEngine;
import com.printscript.domain.result.FormatResult;
import com.printscript.domain.result.InterpretResult;
import com.printscript.domain.result.LintResult;
import com.printscript.domain.result.ValidationResult;
import org.springframework.stereotype.Service;

import java.util.List;

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
public class SnippetOperationService {

    private final FileRepository files;
    private final PrintScriptEngine engine;

    public SnippetOperationService(FileRepository files, PrintScriptEngine engine) {
        this.files = files;
        this.engine = engine;
    }

    public InterpretResult interpret(String snippetKey, String version, List<String> inputs) {
        String code = files.get(snippetKey);
        return engine.interpret(code, version, inputs);
    }

    public LintResult lint(String snippetKey, String rulesKey, String version) {
        String code = files.get(snippetKey);
        String rules = loadRules(rulesKey);
        return engine.lint(code, version, rules);
    }

    public FormatResult format(String snippetKey, String rulesKey, String version) {
        String code = files.get(snippetKey);
        String rules = loadRules(rulesKey);
        return engine.format(code, version, rules);
    }

    /** Sin key de reglas se usan los defaults del engine (config vacia). */
    private String loadRules(String rulesKey) {
        return (rulesKey == null || rulesKey.isBlank()) ? null : files.get(rulesKey);
    }

    public ValidationResult validate(String snippetKey, String version) {
        String code = files.get(snippetKey);
        return engine.validate(code, version);
    }
}
