package com.printscript.config

import com.printscript.websocket.ExecutionWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * WebSocket de ejecucion interactiva. Lo usa snippet-service (cliente de servidor, sin header Origin).
 * Sin setAllowedOrigins: Spring solo acepta el mismo origen, asi un browser de otro sitio no lo puede abrir.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(private val handler: ExecutionWebSocketHandler) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, EXECUTION_PATH)
    }

    companion object {
        const val EXECUTION_PATH = "/printscript/execution"
    }
}
