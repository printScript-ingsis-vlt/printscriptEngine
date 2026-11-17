package com.printscript.websocket

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.printscript.fakes.InMemoryFileRepository
import com.printscript.repository.FileRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["streams.enabled=false"], // sin Redis
)
@Import(ExecutionWebSocketHandlerTest.Files::class)
class ExecutionWebSocketHandlerTest {

    @TestConfiguration
    class Files {
        @Bean
        @Primary
        fun inMemoryFiles(): FileRepository = InMemoryFileRepository(
            "snip" to "println(\"hola\");\nlet a: string = readInput(\"\");\nprintln(a + \"!\");",
            "roto" to "let x: number = ;",
            "simple" to "println(1);",
            "espera" to "let a: string = readInput(\"\");\nprintln(a);",
        )
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var mapper: ObjectMapper

    private val clients = mutableListOf<Client>()

    @AfterEach
    fun closeClients() = clients.forEach { it.session.close() }

    @Test
    fun `corre el snippet pidiendo inputs y manda el output en vivo`() {
        val client = connect()

        client.send("""{"type":"start","snippetKey":"snip","version":"1.1"}""")

        assertMessage(client.next(), "output", "line" to "hola")
        assertEquals("input-request", client.next()["type"].asText())
        client.send("""{"type":"input","value":"uno"}""")
        assertMessage(client.next(), "output", "line" to "uno!")
        val finished = client.next()
        assertEquals("finished", finished["type"].asText())
        assertEquals(0, finished["errors"].size())
        assertEquals(CloseStatus.NORMAL.code, client.closeStatus()?.code)
    }

    @Test
    fun `un error del codigo llega en finished`() {
        val client = connect()

        client.send("""{"type":"start","snippetKey":"roto","version":"1.0"}""")

        val finished = client.next()
        assertEquals("finished", finished["type"].asText())
        assertTrue(finished["errors"].size() > 0)
    }

    @Test
    fun `un snippet inexistente responde FILE_NOT_FOUND y cierra`() {
        val client = connect()

        client.send("""{"type":"start","snippetKey":"nada","version":"1.1"}""")

        assertMessage(client.next(), "error", "code" to "FILE_NOT_FOUND")
        assertNotNull(client.closeStatus())
    }

    @Test
    fun `una version invalida responde UNSUPPORTED_VERSION`() {
        val client = connect()

        client.send("""{"type":"start","snippetKey":"simple","version":"9.9"}""")

        assertMessage(client.next(), "error", "code" to "UNSUPPORTED_VERSION")
    }

    @Test
    fun `start sin snippetKey es INVALID_MESSAGE`() {
        val client = connect()

        client.send("""{"type":"start","version":"1.1"}""")

        assertMessage(client.next(), "error", "code" to "INVALID_MESSAGE")
    }

    @Test
    fun `un mensaje que no es JSON es INVALID_MESSAGE`() {
        val client = connect()

        client.send("no soy json")

        assertMessage(client.next(), "error", "code" to "INVALID_MESSAGE")
    }

    @Test
    fun `input antes de start es NOT_STARTED`() {
        val client = connect()

        client.send("""{"type":"input","value":"x"}""")

        assertMessage(client.next(), "error", "code" to "NOT_STARTED")
    }

    @Test
    fun `stop cierra la conexion y corta el programa`() {
        val client = connect()
        client.send("""{"type":"start","snippetKey":"espera","version":"1.1"}""")
        assertEquals("input-request", client.next()["type"].asText())

        client.send("""{"type":"stop"}""")

        assertNotNull(client.closeStatus())
        // afterConnectionClosed llega antes de que la sesion del cliente se marque cerrada: en una maquina
        // lenta (CI) isOpen todavia puede dar true un instante, asi que se espera en vez de mirarlo una vez.
        assertTrue(client.awaitSessionClosed(), "La sesion del cliente sigue abierta")
        client.assertNoMore()
    }

    // ------------------------------------------------------------------ helpers

    private fun connect(): Client {
        val client = Client(mapper)
        client.session = StandardWebSocketClient()
            .execute(client, "ws://localhost:$port/printscript/execution")
            .get(5, TimeUnit.SECONDS)
        clients.add(client)
        return client
    }

    private fun assertMessage(message: JsonNode, type: String, field: Pair<String, String>) {
        assertEquals(type, message["type"].asText(), message.toString())
        assertEquals(field.second, message[field.first].asText(), message.toString())
    }

    class Client(private val mapper: ObjectMapper) : TextWebSocketHandler() {
        lateinit var session: WebSocketSession
        private val messages = LinkedBlockingQueue<JsonNode>()
        private val closed = LinkedBlockingQueue<CloseStatus>()

        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            messages.add(mapper.readTree(message.payload))
        }

        override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
            closed.add(status)
        }

        fun send(payload: String) = session.sendMessage(TextMessage(payload))

        fun next(): JsonNode = messages.poll(5, TimeUnit.SECONDS) ?: fail("No llego ningun mensaje")

        fun closeStatus(): CloseStatus? = closed.poll(5, TimeUnit.SECONDS)

        /** true si la sesion queda cerrada dentro de [timeoutMs]. */
        fun awaitSessionClosed(timeoutMs: Long = 5_000): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (session.isOpen && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
            return !session.isOpen
        }

        fun assertNoMore() {
            val extra = messages.poll(300, TimeUnit.MILLISECONDS)
            assertEquals(null, extra, "Llego un mensaje de mas")
        }

        private companion object {
            const val POLL_MS = 20L
        }
    }
}
