package com.printscript.controller;

import com.printscript.controller.request.InterpretRequest;
import com.printscript.controller.request.RulesOperationRequest;
import com.printscript.domain.result.FormatResult;
import com.printscript.domain.result.InterpretResult;
import com.printscript.domain.result.LintResult;
import com.printscript.domain.result.ValidationResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.printscript.service.SnippetOperationService;

/**
 * Sin logica: valida la entrada (@Valid) y delega. Los errores los traduce ApiExceptionHandler.
 * Criterio: errores del codigo del usuario (sintaxis, lint, ejecucion) son parte del resultado
 * y devuelven 200; los errores del servicio devuelven 4xx/5xx.
 */
@RestController
@RequestMapping("/printscript")
public class SnippetOperationController {

    private final SnippetOperationService service;

    public SnippetOperationController(SnippetOperationService service) {
        this.service = service;
    }

    @PostMapping("/interpret")
    public InterpretResult interpret(@Valid @RequestBody InterpretRequest request) {
        return service.interpret(request.snippetKey(), request.version(), request.inputs());
    }

    @PostMapping("/lint")
    public LintResult lint(@Valid @RequestBody RulesOperationRequest request) {
        return service.lint(request.snippetKey(), request.rulesKey(), request.version());
    }

    @PostMapping("/format")
    public FormatResult format(@Valid @RequestBody RulesOperationRequest request) {
        return service.format(request.snippetKey(), request.rulesKey(), request.version());
    }

    @PostMapping("/validate")
    public ValidationResult validate(
            @Valid @RequestBody RulesOperationRequest request
    ) {
        return service.validate(request.snippetKey(), request.version());
    }
}
