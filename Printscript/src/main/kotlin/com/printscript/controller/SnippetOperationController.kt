package com.printscript.controller

import com.printscript.controller.request.InterpretRequest
import com.printscript.controller.request.RulesOperationRequest
import com.printscript.domain.result.FormatResult
import com.printscript.domain.result.InterpretResult
import com.printscript.domain.result.LintResult
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
    fun format(@Valid @RequestBody request: RulesOperationRequest): FormatResult =
        service.format(request.snippetKey!!, request.rulesKey, request.version!!)

    @PostMapping("/validate")
    fun validate(@Valid @RequestBody request: RulesOperationRequest): ValidationResult =
        service.validate(request.snippetKey!!, request.version!!)
}
