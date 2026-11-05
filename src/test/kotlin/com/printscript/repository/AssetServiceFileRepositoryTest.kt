package com.printscript.repository

import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.domain.errors.StorageUnavailableException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetServiceFileRepositoryTest {

    private val builder = RestClient.builder().baseUrl(BASE_URL)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val repo = AssetServiceFileRepository(builder.build(), "snippets")

    @AfterEach
    fun verify() = server.verify()

    @Test
    fun `get devuelve el contenido decodificado en UTF-8`() {
        val code = "println(\"canción ñandú\");"
        server.expect(requestTo("$BASE_URL/v1/asset/snippets/hola.ps"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(code.toByteArray(Charsets.UTF_8), MediaType.APPLICATION_OCTET_STREAM))

        assertEquals(code, repo.get("hola.ps"))
    }

    @Test
    fun `get de un archivo vacio devuelve string vacio`() {
        server.expect(requestTo("$BASE_URL/v1/asset/snippets/vacio.ps"))
            .andRespond(withStatus(HttpStatus.OK))

        assertEquals("", repo.get("vacio.ps"))
    }

    @Test
    fun `get de una key inexistente lanza StorageFileNotFoundException`() {
        server.expect(requestTo("$BASE_URL/v1/asset/snippets/no-existe.ps"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertFailsWith<StorageFileNotFoundException> { repo.get("no-existe.ps") }
    }

    @Test
    fun `get con error 5xx lanza StorageUnavailableException`() {
        server.expect(requestTo("$BASE_URL/v1/asset/snippets/hola.ps"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertFailsWith<StorageUnavailableException> { repo.get("hola.ps") }
    }

    @Test
    fun `get con el servicio caido lanza StorageUnavailableException`() {
        server.expect(requestTo("$BASE_URL/v1/asset/snippets/hola.ps"))
            .andRespond(withException(IOException("Connection refused")))

        assertFailsWith<StorageUnavailableException> { repo.get("hola.ps") }
    }

    @Test
    fun `put manda el contenido en UTF-8 con PUT`() {
        val code = "let año: number = 1;"
        server.expect(requestTo("$BASE_URL/v1/asset/snippets/hola.ps.formatted"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().bytes(code.toByteArray(Charsets.UTF_8)))
            .andRespond(withCreatedEntity(URI("/v1/asset/snippets/hola.ps.formatted")))

        repo.put("hola.ps.formatted", code)
    }

    @Test
    fun `put que actualiza un archivo existente no falla`() {
        server.expect(requestTo("$BASE_URL/v1/asset/snippets/hola.ps.formatted"))
            .andRespond(withSuccess())

        repo.put("hola.ps.formatted", "let x: number = 1;")
    }

    @Test
    fun `put con error 5xx lanza StorageUnavailableException`() {
        server.expect(requestTo("$BASE_URL/v1/asset/snippets/hola.ps.formatted"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertFailsWith<StorageUnavailableException> { repo.put("hola.ps.formatted", "x") }
    }

    companion object {
        private const val BASE_URL = "http://asset.test"
    }
}
