package com.printscript.repository

import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.domain.errors.StorageUnavailableException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Repository
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * FileRepository real: lee y escribe archivos a traves del Asset Service
 * (GET/PUT /v1/asset/{container}/{key}), que es quien habla con el bucket.
 *
 * El contenido viaja como bytes y se decodifica en UTF-8 a mano: si se pidiera String directo,
 * sin charset en la respuesta Spring asume ISO-8859-1 y rompe los acentos.
 */
@Repository
class AssetServiceFileRepository(
    private val assetRestClient: RestClient,
    @Value("\${asset.container:snippets}") private val container: String,
) : FileRepository {

    override fun get(key: String): String = call(key) {
        val bytes = assetRestClient.get()
            .uri(ASSET_PATH, container, key)
            .retrieve()
            .body(ByteArray::class.java)
        bytes?.toString(Charsets.UTF_8).orEmpty()
    }

    override fun put(key: String, content: String) = call(key) {
        assetRestClient.put()
            .uri(ASSET_PATH, container, key)
            .contentType(MediaType(MediaType.TEXT_PLAIN, Charsets.UTF_8))
            .body(content.toByteArray(Charsets.UTF_8))
            .retrieve()
            .toBodilessEntity()
        Unit
    }

    /** 404 -> el archivo no existe; cualquier otro error (5xx, conexion, timeout) -> storage caido. */
    private fun <T> call(key: String, block: () -> T): T =
        try {
            block()
        } catch (e: HttpClientErrorException.NotFound) {
            throw StorageFileNotFoundException(key, e)
        } catch (e: RestClientException) {
            throw StorageUnavailableException("El Asset Service fallo al acceder a $container/$key", e)
        }

    companion object {
        private const val ASSET_PATH = "/v1/asset/{container}/{key}"
    }
}
