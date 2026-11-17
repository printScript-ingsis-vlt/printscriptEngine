package com.printscript.websocket

/**
 * Protocolo de `/printscript/execution` (JSON, un mensaje por frame de texto).
 *
 * Cliente -> engine:
 * - `{"type":"start","snippetKey":"...","version":"1.1"}`: primer mensaje, arranca la ejecucion.
 * - `{"type":"input","value":"..."}`: respuesta a un readInput (o adelantada: queda en cola).
 * - `{"type":"stop"}`: corta la ejecucion. Cerrar el socket tambien corta.
 *
 * Engine -> cliente: ver [ServerMessage]. Despues de `finished` o `error` el engine cierra el socket.
 */
data class ClientMessage(
    val type: String? = null,
    val snippetKey: String? = null,
    val version: String? = null,
    val value: String? = null,
) {
    companion object {
        const val START = "start"
        const val INPUT = "input"
        const val STOP = "stop"
    }
}

sealed interface ServerMessage {
    val type: String
}

/** Una linea impresa (println o el prompt de readInput). */
data class OutputMessage(val line: String) : ServerMessage {
    override val type = "output"
}

/** El programa esta esperando un input. */
class InputRequestMessage : ServerMessage {
    override val type = "input-request"
}

/** Termino. Los errores son del codigo del usuario (sintaxis, ejecucion, timeout, input que no llego). */
data class FinishedMessage(val errors: List<String>) : ServerMessage {
    override val type = "finished"
}

/** Error del servicio o del protocolo: no se pudo arrancar o el mensaje no es valido. */
data class ErrorMessage(val code: ErrorCode, val message: String?) : ServerMessage {
    override val type = "error"
}

enum class ErrorCode {
    INVALID_MESSAGE,
    ALREADY_STARTED,
    NOT_STARTED,
    FILE_NOT_FOUND,
    STORAGE_UNAVAILABLE,
    UNSUPPORTED_VERSION,
    INTERNAL_ERROR,
}
