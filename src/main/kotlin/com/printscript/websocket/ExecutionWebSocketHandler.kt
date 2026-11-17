package com.printscript.websocket

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.domain.errors.StorageUnavailableException
import com.printscript.domain.errors.UnsupportedVersionException
import com.printscript.domain.interactive.ExecutionHandle
import com.printscript.domain.interactive.ExecutionListener
import com.printscript.service.SnippetOperationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Ejecucion interactiva por WebSocket: una conexion = una ejecucion. Protocolo en [ClientMessage].
 *
 * Los permisos los valida snippet-service antes de abrir este socket (igual que en el resto de los endpoints).
 * Cerrar la conexion corta el programa; un `error` o `finished` cierra la conexion.
 */
@Component
class ExecutionWebSocketHandler(
    private val service: SnippetOperationService,
    private val mapper: ObjectMapper,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(ExecutionWebSocketHandler::class.java)

    // Por id de sesion. La sesion va decorada: el hilo del interprete, el watchdog y este handler mandan
    // mensajes a la vez, y WebSocketSession.sendMessage no es thread-safe.
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()
    private val executions = ConcurrentHashMap<String, ExecutionHandle>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions[session.id] = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val safe = sessions[session.id] ?: return
        val msg = parse(message.payload)
            ?: return fail(safe, ErrorCode.INVALID_MESSAGE, "El mensaje no es un JSON valido")

        when (msg.type) {
            ClientMessage.START -> start(safe, msg)
            ClientMessage.INPUT -> executions[session.id]?.sendInput(msg.value.orEmpty())
                ?: fail(safe, ErrorCode.NOT_STARTED, "Primero hay que mandar start")
            ClientMessage.STOP -> close(safe)
            else -> fail(safe, ErrorCode.INVALID_MESSAGE, "Tipo de mensaje desconocido: ${msg.type}")
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessions.remove(session.id)
        executions.remove(session.id)?.cancel()
    }

    private fun start(session: WebSocketSession, msg: ClientMessage) {
        if (executions.containsKey(session.id)) {
            return fail(session, ErrorCode.ALREADY_STARTED, "Ya hay una ejecucion en esta conexion")
        }
        val snippetKey = msg.snippetKey
        val version = msg.version
        if (snippetKey.isNullOrBlank() || version.isNullOrBlank()) {
            return fail(session, ErrorCode.INVALID_MESSAGE, "start necesita snippetKey y version")
        }
        try {
            executions[session.id] = service.startExecution(snippetKey, version, SessionListener(session))
            // Un programa corto puede terminar (y cerrar) antes del put: que no quede el handle colgado.
            if (!session.isOpen) executions.remove(session.id)
        } catch (e: StorageFileNotFoundException) {
            fail(session, ErrorCode.FILE_NOT_FOUND, e.message)
        } catch (e: StorageUnavailableException) {
            log.error("Storage no disponible", e)
            fail(session, ErrorCode.STORAGE_UNAVAILABLE, "El almacenamiento no esta disponible")
        } catch (e: UnsupportedVersionException) {
            fail(session, ErrorCode.UNSUPPORTED_VERSION, e.message)
        }
    }

    /** Pasa los eventos de la ejecucion al socket. Al terminar, cierra. */
    private inner class SessionListener(private val session: WebSocketSession) : ExecutionListener {
        override fun onOutput(line: String) = send(session, OutputMessage(line))

        override fun onInputRequested() = send(session, InputRequestMessage())

        override fun onFinished(errors: List<String>) {
            send(session, FinishedMessage(errors))
            close(session)
        }
    }

    private fun parse(payload: String): ClientMessage? =
        try {
            mapper.readValue(payload, ClientMessage::class.java)
        } catch (_: JacksonException) {
            null
        }

    private fun fail(session: WebSocketSession, code: ErrorCode, message: String?) {
        send(session, ErrorMessage(code, message))
        close(session)
    }

    // Si el cliente ya se fue, no hay a quien avisar: afterConnectionClosed corta la ejecucion.
    private fun send(session: WebSocketSession, message: ServerMessage) {
        if (!session.isOpen) return
        try {
            session.sendMessage(TextMessage(mapper.writeValueAsString(message)))
        } catch (e: IOException) {
            log.debug("No se pudo mandar {} a la sesion {}", message.type, session.id, e)
        }
    }

    private fun close(session: WebSocketSession) {
        try {
            session.close(CloseStatus.NORMAL)
        } catch (e: IOException) {
            log.debug("No se pudo cerrar la sesion {}", session.id, e)
        }
    }

    private companion object {
        const val SEND_TIME_LIMIT_MS = 5_000
        const val BUFFER_SIZE_LIMIT = 512 * 1024
    }
}
