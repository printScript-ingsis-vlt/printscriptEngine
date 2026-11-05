package com.printscript.controller.request

import jakarta.validation.constraints.NotBlank

// Los campos son nullable para que un JSON sin el campo llegue a @Valid (400 con mensaje)
// en vez de fallar al deserializar. Despues de @Valid se pueden usar con !!.

data class InterpretRequest(
    @field:NotBlank val snippetKey: String?,
    @field:NotBlank val version: String?,
    val inputs: List<String>?,
)

/** Request de lint y format. rulesKey es opcional: sin ella se usan los defaults. */
data class RulesOperationRequest(
    @field:NotBlank val snippetKey: String?,
    val rulesKey: String?,
    @field:NotBlank val version: String?,
)
