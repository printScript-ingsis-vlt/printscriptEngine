package com.printscript.controller

import com.printscript.controller.request.InterpretRequest
import com.printscript.controller.request.RulesOperationRequest
import com.printscript.controller.request.RunTestRequest
import com.printscript.controller.request.ValidateRequest
import com.printscript.domain.result.FormatOperationResult
import com.printscript.domain.result.InterpretResult
import com.printscript.domain.result.LintResult
import com.printscript.domain.result.TestRunResult
import com.printscript.domain.result.ValidationResult
import com.printscript.service.SnippetOperationService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Sin logica: valida la entrada (@Valid) y delega. Los errores los traduce ApiExceptionHandler.
 * Criterio: errores del codigo del usuario (sintaxis, lint, ejecucion) son parte del resultado
 * y devuelven 200; los errores del servicio devuelven 4xx/5xx.
 */
@RestController
@RequestMapping("/printscript")
class SnippetOperationController(private val service: SnippetOperationService) {

    @PostMapping("/interpret")
    fun interpret(@Valid @RequestBody request: InterpretRequest): InterpretResult =
        service.interpret(request.snippetKey!!, request.version!!, request.inputs)

    @PostMapping("/lint")
    fun lint(@Valid @RequestBody request: RulesOperationRequest): LintResult =
        service.lint(request.snippetKey!!, request.rulesKey, request.version!!)

    @PostMapping("/format")
    fun format(@Valid @RequestBody request: RulesOperationRequest): FormatOperationResult =
        service.format(request.snippetKey!!, request.rulesKey, request.version!!)

    /** Valida el codigo de `content` (sin guardarlo) o el del snippet `snippetKey` ya guardado: uno solo de los dos. */
    @PostMapping("/validate")
    fun validate(@Valid @RequestBody request: ValidateRequest): ValidationResult =
        service.validate(request.snippetKey, request.content, request.version!!)

    /** Corre un test: un test que no pasa (o un snippet que falla al ejecutar) tambien es 200. */
    @PostMapping("/test")
    fun runTest(@Valid @RequestBody request: RunTestRequest): TestRunResult =
        service.runTest(
            request.snippetKey!!,
            request.version!!,
            request.inputs.orEmpty(),
            request.expectedOutputs!!,
        )
}
