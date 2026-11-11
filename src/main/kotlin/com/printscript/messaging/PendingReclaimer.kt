package com.printscript.messaging

import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled

/**
 * Reintenta los pedidos que quedaron entregados pero sin ack, porque el consumer murio o fallo
 * con un error transitorio.
 *
 * Solo toma los que llevan mas de `reclaim-idle` sin ack (XPENDING). Si ya se entregaron `max-deliveries`
 * veces, los manda a la DLQ y responde FAILED. Si no, los reclama para este consumer (XCLAIM, que suma una
 * entrega) y los vuelve a procesar.
 */
class PendingReclaimer(
    private val redis: StringRedisTemplate,
    private val properties: StreamProperties,
    private val handler: RuleRequestHandler,
) {

    @Scheduled(
        initialDelayString = "\${streams.reclaim-interval:PT30S}",
        fixedDelayString = "\${streams.reclaim-interval:PT30S}",
    )
    fun reclaimAll() {
        RuleOperation.entries.forEach { operation ->
            runCatching { reclaim(operation) }
                .onFailure { log.warn("Fallo el reclamo de pendientes de {}: {}", operation, it.message) }
        }
    }

    fun reclaim(operation: RuleOperation) {
        val stream = properties.requestStream(operation)
        val streams = redis.opsForStream<String, String>()
        val pending = streams.pending(stream, properties.group, Range.unbounded<String>(), BATCH_SIZE) ?: return

        pending.filter { it.elapsedTimeSinceLastDelivery >= properties.reclaimIdle }.forEach { message ->
            if (message.totalDeliveryCount >= properties.maxDeliveries) {
                discard(operation, stream, message.id, message.totalDeliveryCount)
            } else {
                retry(operation, stream, message.id)
            }
        }
    }

    private fun discard(operation: RuleOperation, stream: String, id: RecordId, deliveries: Long) {
        val streams = redis.opsForStream<String, String>()
        // El record puede no estar si MAXLEN ya lo recorto: en ese caso solo se limpia el pendiente.
        val record = streams.range(stream, Range.just(id.value))?.firstOrNull()
        if (record != null) {
            handler.handleExhausted(operation, record.value, "max deliveries ($deliveries) exceeded")
        }
        streams.acknowledge(stream, properties.group, id)
    }

    private fun retry(operation: RuleOperation, stream: String, id: RecordId) {
        val streams = redis.opsForStream<String, String>()
        streams.claim(stream, properties.group, properties.consumer, properties.reclaimIdle, id).orEmpty()
            .filter { handler.handle(operation, it.value) }
            .forEach { streams.acknowledge(stream, properties.group, it.id) }
    }

    private companion object {
        const val BATCH_SIZE = 100L
        val log = LoggerFactory.getLogger(PendingReclaimer::class.java)
    }
}
