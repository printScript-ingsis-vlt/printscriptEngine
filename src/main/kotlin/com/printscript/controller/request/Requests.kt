package com.printscript.controller.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

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

/**
 * Request de validate: el codigo viene de uno solo de los dos lados.
 * - [content]: el texto del codigo, sin que este guardado (sirve para validar antes de guardar).
 * - [snippetKey]: un snippet que ya esta en el Asset Service.
 * Que venga exactamente uno de los dos lo chequea el servicio (si no, 400).
 */
data class ValidateRequest(
    val snippetKey: String?,
    @field:Size(max = MAX_CONTENT_LENGTH) val content: String?,
    @field:NotBlank val version: String?,
) {
    companion object {
        /** Tope del codigo que se acepta por el body, para que nadie mande textos enormes al parser. */
        const val MAX_CONTENT_LENGTH = 1_000_000
    }
}

/**
 * expectedOutputs es obligatorio: si faltara (o viniera mal escrito) se tomaria como lista vacia y un snippet
 * que no imprime nada pasaria el test. Para esperar "no imprime nada" hay que mandar [] explicito.
 * inputs es opcional: sin inputs el snippet no puede leer nada.
 */
data class RunTestRequest(
    @field:NotBlank val snippetKey: String?,
    @field:NotBlank val version: String?,
    val inputs: List<String>?,
    @field:NotNull val expectedOutputs: List<String>?,
)
