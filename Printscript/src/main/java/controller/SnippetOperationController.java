package controller;

import controller.request.InterpretRequest;
import controller.request.RulesOperationRequest;
import domain.result.FormatResult;
import domain.result.InterpretResult;
import domain.result.LintResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import service.SnippetOperationService;

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
}