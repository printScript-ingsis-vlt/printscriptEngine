package com.printscript.messaging

/** Salida del engine hacia los streams. La implementacion real es [RedisResultPublisher]. */
interface ResultPublisher {
    fun publishLint(result: LintResultMessage)

    fun publishFormat(result: FormatResultMessage)

    /** Guarda el pedido original en `{stream}-dlq` con el motivo, para poder inspeccionarlo despues. */
    fun deadLetter(operation: RuleOperation, fields: Map<String, String>, reason: String)
}
