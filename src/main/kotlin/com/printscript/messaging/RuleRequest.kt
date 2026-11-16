package com.printscript.messaging

/** Operacion que pide snippet-service cuando cambian las reglas de un usuario. */
enum class RuleOperation { LINT, FORMAT }

/**
 * Pedido de lint o format (contrato v1). Viaja como un mapa plano String -> String para que
 * snippet-service y el engine no tengan que compartir clases ni version de Jackson.
 *
 * Solo lleva keys del Asset Service: el engine lee el codigo y las reglas desde ahi.
 */
data class RuleRequest(
    val requestId: String,
    val snippetId: String,
    val snippetKey: String,
    val rulesKey: String?,
    val language: String,
    val version: String,
) {
    companion object {
        const val SCHEMA_VERSION = "1"

        /** Lanza [InvalidMessageException] si falta un campo obligatorio o la version del contrato no es la 1. */
        fun from(fields: Map<String, String>): RuleRequest {
            val schema = fields[Fields.SCHEMA_VERSION]
            if (schema != SCHEMA_VERSION) throw InvalidMessageException("schemaVersion no soportada: $schema")
            return RuleRequest(
                requestId = fields.required(Fields.REQUEST_ID),
                snippetId = fields.required(Fields.SNIPPET_ID),
                // Hoy la key del Asset Service es el id del snippet: si no viene, se usa ese.
                snippetKey = fields[Fields.SNIPPET_KEY]?.takeIf { it.isNotBlank() }
                    ?: fields.required(Fields.SNIPPET_ID),
                rulesKey = fields[Fields.RULES_KEY]?.takeIf { it.isNotBlank() },
                language = fields.required(Fields.LANGUAGE),
                version = fields.required(Fields.VERSION),
            )
        }

        private fun Map<String, String>.required(name: String): String =
            this[name]?.takeIf { it.isNotBlank() } ?: throw InvalidMessageException("Falta el campo $name")
    }
}

/** Nombres de los campos de los mensajes. Son el contrato con snippet-service: no renombrar. */
object Fields {
    const val SCHEMA_VERSION = "schemaVersion"
    const val REQUEST_ID = "requestId"
    const val SNIPPET_ID = "snippetId"
    const val SNIPPET_KEY = "snippetKey"
    const val RULES_KEY = "rulesKey"
    const val LANGUAGE = "language"
    const val VERSION = "version"
    const val STATUS = "status"
    const val ISSUES = "issues"
    const val ERRORS = "errors"
    const val FORMATTED = "formatted"
    const val REASON = "reason"
}

/** El mensaje no respeta el contrato: no tiene sentido reintentarlo. */
class InvalidMessageException(message: String) : RuntimeException(message)
