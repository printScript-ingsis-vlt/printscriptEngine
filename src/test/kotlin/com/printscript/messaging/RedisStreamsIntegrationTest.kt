package com.printscript.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.printscript.fakes.InMemoryFileRepository
import com.printscript.repository.FileRepository
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ida y vuelta contra un Redis real (Testcontainers, necesita Docker): XADD de un pedido, el listener del
 * engine lo procesa y aparece la respuesta en el stream de resultados. El Asset Service es un fake en memoria.
 */
@SpringBootTest(
    properties = [
        "streams.max-deliveries=2",
        "streams.reclaim-idle=PT0.2S",
        // El reclaimer se invoca a mano en el test de reintentos.
        "streams.reclaim-interval=PT1H",
    ],
)
@Testcontainers
class RedisStreamsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redisContainer: GenericContainer<*> = GenericContainer("redis:7.4-alpine").withExposedPorts(6379)

        val files = InMemoryFileRepository(
            "snippet" to "let mi_variable: number = 5;\nprintln(mi_variable);",
            "camel" to """{"identifier-format":"camel-case"}""",
            "noSpaces" to """{"enforce-no-spacing-around-equals":true}""",
        )

        private val TIMEOUT: Duration = Duration.ofSeconds(10)
    }

    @TestConfiguration
    class FakeAssetService {
        @Bean
        @Primary
        fun fileRepository(): FileRepository = files
    }

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var properties: StreamProperties

    @Autowired
    lateinit var reclaimer: PendingReclaimer

    private val json = jacksonObjectMapper()

    @BeforeEach
    fun reset() {
        files.unavailable = false
        files.writes.clear()
    }

    private fun publishRequest(stream: String, requestId: String, rulesKey: String?) {
        val fields = mapOf(
            "schemaVersion" to "1",
            "requestId" to requestId,
            "snippetId" to "snip-$requestId",
            "snippetKey" to "snippet",
            "rulesKey" to rulesKey.orEmpty(),
            "language" to "printscript",
            "version" to "1.0",
        )
        redis.opsForStream<String, String>().add(StreamRecords.string(fields).withStreamKey(stream))
    }

    private fun findIn(stream: String, requestId: String): MapRecord<String, String, String>? =
        redis.opsForStream<String, String>().range(stream, Range.unbounded<String>())
            ?.firstOrNull { it.value["requestId"] == requestId }

    private fun pendingCount(stream: String): Long =
        redis.opsForStream<String, String>().pending(stream, properties.group)?.totalPendingMessages ?: 0

    private fun contractKeys(name: String): Set<String> =
        json.readValue<Map<String, String>>(javaClass.getResource("/contracts/$name")!!).keys

    @Test
    fun `un pedido de lint produce su respuesta con las claves del contrato y queda con ack`() {
        val requestId = UUID.randomUUID().toString()
        publishRequest(properties.lintRequests, requestId, "camel")

        val result = await.atMost(TIMEOUT).untilNotNull { findIn(properties.lintResults, requestId) }

        assertEquals(contractKeys("lint-result.v1.json"), result.value.keys)
        assertEquals("NOT_COMPLIANT", result.value["status"])
        assertEquals("snip-$requestId", result.value["snippetId"])
        val issues = json.readValue<List<Map<String, Any>>>(result.value.getValue("issues"))
        assertEquals("identifier-format", issues.single()["rule"])
        await.atMost(TIMEOUT).until { pendingCount(properties.lintRequests) == 0L }
    }

    @Test
    fun `un pedido de format devuelve el codigo formateado sin escribir en el Asset Service`() {
        val requestId = UUID.randomUUID().toString()
        publishRequest(properties.formatRequests, requestId, "noSpaces")

        val result = await.atMost(TIMEOUT).untilNotNull { findIn(properties.formatResults, requestId) }

        assertEquals(contractKeys("format-result.v1.json"), result.value.keys)
        assertEquals("FORMATTED", result.value["status"])
        assertTrue(result.value.getValue("formatted").contains("mi_variable: number=5;"))
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `con el Asset Service caido se reintenta y al agotar las entregas va a la DLQ con FAILED`() {
        files.unavailable = true
        val requestId = UUID.randomUUID().toString()
        publishRequest(properties.lintRequests, requestId, null)

        // Primera entrega: falla y queda pendiente (sin ack).
        await.atMost(TIMEOUT).until { pendingCount(properties.lintRequests) == 1L }

        // Segunda entrega por el reclaimer: vuelve a fallar.
        await.pollDelay(Duration.ofMillis(300)).atMost(TIMEOUT).until {
            reclaimer.reclaim(RuleOperation.LINT)
            redis.opsForStream<String, String>()
                .pending(properties.lintRequests, properties.group, Range.unbounded<String>(), 10)
                ?.firstOrNull()?.totalDeliveryCount == 2L
        }

        // Tercera vez: supero max-deliveries, se descarta.
        await.pollDelay(Duration.ofMillis(300)).atMost(TIMEOUT).untilNotNull {
            reclaimer.reclaim(RuleOperation.LINT)
            findIn(properties.lintResults, requestId)
        }

        assertEquals("FAILED", findIn(properties.lintResults, requestId)!!.value["status"])
        val dead = findIn(properties.deadLetterStream(RuleOperation.LINT), requestId)!!
        assertTrue(dead.value.getValue("reason").contains("max deliveries"))
        assertEquals(0L, pendingCount(properties.lintRequests))
    }
}
