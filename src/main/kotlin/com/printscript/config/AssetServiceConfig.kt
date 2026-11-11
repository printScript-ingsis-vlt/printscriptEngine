package com.printscript.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Cliente HTTP hacia el Asset Service (que guarda los archivos en el bucket).
 * Con timeouts para que un bucket colgado no deje colgado al engine: si vencen,
 * el repositorio lo traduce a StorageUnavailableException (503).
 */
@Configuration
class AssetServiceConfig {

    @Bean
    fun assetRestClient(
        builder: RestClient.Builder,
        @Value("\${asset.url}") baseUrl: String,
        @Value("\${asset.connect-timeout:2s}") connectTimeout: Duration,
        @Value("\${asset.read-timeout:10s}") readTimeout: Duration,
    ): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(connectTimeout)
            setReadTimeout(readTimeout)
        }
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build()
    }
}
