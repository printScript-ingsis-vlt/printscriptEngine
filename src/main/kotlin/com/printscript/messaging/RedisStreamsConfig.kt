package com.printscript.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.printscript.service.SnippetOperationService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamListener
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Duration

private typealias StringRecord = MapRecord<String, String, String>

/**
 * Engancha el engine a los streams de pedidos: un consumer group por servicio (`printscript-engine`), con
 * ack manual. Con `streams.enabled=false` no se crea nada de esto: la API HTTP funciona igual sin Redis.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StreamProperties::class)
@ConditionalOnProperty(prefix = "streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RedisStreamsConfig {

    @Bean
    fun resultPublisher(redis: StringRedisTemplate, properties: StreamProperties, json: ObjectMapper): ResultPublisher =
        RedisResultPublisher(redis, properties, json)

    @Bean
    fun ruleRequestHandler(service: SnippetOperationService, publisher: ResultPublisher) =
        RuleRequestHandler(RuleRequestProcessor(service), publisher)

    @Bean
    fun pendingReclaimer(redis: StringRedisTemplate, properties: StreamProperties, handler: RuleRequestHandler) =
        PendingReclaimer(redis, properties, handler)

    @Bean(initMethod = "start", destroyMethod = "stop")
    fun ruleRequestListenerContainer(
        connectionFactory: RedisConnectionFactory,
        redis: StringRedisTemplate,
        properties: StreamProperties,
        handler: RuleRequestHandler,
    ): StreamMessageListenerContainer<String, StringRecord> {
        val options = StreamMessageListenerContainerOptions.builder()
            .pollTimeout(POLL_TIMEOUT)
            .batchSize(BATCH_SIZE)
            .errorHandler { log.warn("Error leyendo los streams de pedidos: {}", it.message) }
            .build()
        val container = StreamMessageListenerContainer.create(connectionFactory, options)

        RuleOperation.entries.forEach { operation ->
            val stream = properties.requestStream(operation)
            ensureGroup(redis, stream, properties.group)
            val listener = StreamListener<String, StringRecord> { record ->
                if (handler.handle(operation, record.value)) {
                    redis.opsForStream<String, String>().acknowledge(stream, properties.group, record.id)
                }
            }
            val request = StreamReadRequest.builder(StreamOffset.create(stream, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(properties.group, properties.consumer))
                .autoAcknowledge(false)
                // Por defecto un error cancela la suscripcion y el engine deja de leer para siempre.
                .cancelOnError { false }
                .build()
            container.register(request, listener)
        }
        return container
    }

    /**
     * XGROUP CREATE con MKSTREAM desde el offset 0, para que tambien se procesen los pedidos publicados
     * antes de que el engine arrancara por primera vez. Si el grupo ya existe (BUSYGROUP), no hace nada.
     */
    private fun ensureGroup(redis: StringRedisTemplate, stream: String, group: String) {
        try {
            redis.opsForStream<String, String>().createGroup(stream, ReadOffset.from("0"), group)
        } catch (e: RedisSystemException) {
            if (e.rootCause?.message?.contains("BUSYGROUP") != true) throw e
        }
    }

    private companion object {
        val POLL_TIMEOUT: Duration = Duration.ofSeconds(2)
        const val BATCH_SIZE = 10
        val log = LoggerFactory.getLogger(RedisStreamsConfig::class.java)
    }
}
