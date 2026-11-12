package com.printscript.messaging

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Nombres de los streams y parametros de reintento (prefijo `streams.` en application.properties). */
@ConfigurationProperties(prefix = "streams")
data class StreamProperties(
    val enabled: Boolean = true,
    val group: String = "printscript-engine",
    val consumer: String = "printscript-engine-local",
    val lintRequests: String = "lint-requests",
    val formatRequests: String = "format-requests",
    val lintResults: String = "lint-results",
    val formatResults: String = "format-results",
    val deadLetterSuffix: String = "-dlq",
    val maxDeliveries: Long = 3,
    val reclaimIdle: Duration = DEFAULT_RECLAIM_IDLE,
    val maxLength: Long = DEFAULT_MAX_LENGTH,
) {
    fun requestStream(operation: RuleOperation): String = when (operation) {
        RuleOperation.LINT -> lintRequests
        RuleOperation.FORMAT -> formatRequests
    }

    fun deadLetterStream(operation: RuleOperation): String = requestStream(operation) + deadLetterSuffix

    private companion object {
        val DEFAULT_RECLAIM_IDLE: Duration = Duration.ofSeconds(60)
        const val DEFAULT_MAX_LENGTH = 10_000L
    }
}
