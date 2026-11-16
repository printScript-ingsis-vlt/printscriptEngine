package com.printscript.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Publica con XADD. Los campos con listas (issues, errors) van como un string JSON dentro del mapa plano.
 * MAXLEN aproximado para que los streams no crezcan sin limite.
 */
class RedisResultPublisher(
    private val redis: StringRedisTemplate,
    private val properties: StreamProperties,
    private val json: ObjectMapper,
) : ResultPublisher {

    override fun publishLint(result: LintResultMessage) = add(
        properties.lintResults,
        mapOf(
            Fields.SCHEMA_VERSION to RuleRequest.SCHEMA_VERSION,
            Fields.REQUEST_ID to result.requestId,
            Fields.SNIPPET_ID to result.snippetId,
            Fields.STATUS to result.status.name,
            Fields.ISSUES to json.writeValueAsString(result.issues),
            Fields.ERRORS to json.writeValueAsString(result.errors),
        ),
    )

    override fun publishFormat(result: FormatResultMessage) = add(
        properties.formatResults,
        mapOf(
            Fields.SCHEMA_VERSION to RuleRequest.SCHEMA_VERSION,
            Fields.REQUEST_ID to result.requestId,
            Fields.SNIPPET_ID to result.snippetId,
            Fields.STATUS to result.status.name,
            Fields.FORMATTED to result.formatted.orEmpty(),
            Fields.ERRORS to json.writeValueAsString(result.errors),
        ),
    )

    override fun deadLetter(operation: RuleOperation, fields: Map<String, String>, reason: String) =
        add(properties.deadLetterStream(operation), fields + (Fields.REASON to reason))

    private fun add(stream: String, fields: Map<String, String>) {
        val record = StreamRecords.string(fields).withStreamKey(stream)
        redis.opsForStream<String, String>()
            .add(record, XAddOptions.maxlen(properties.maxLength).approximateTrimming(true))
    }
}
