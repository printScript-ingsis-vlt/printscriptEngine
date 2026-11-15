package com.printscript.controller

import com.printscript.domain.errors.ExecutionTimeoutException
import com.printscript.domain.errors.InvalidFormatConfigException
import com.printscript.domain.errors.InvalidLintConfigException
import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.domain.errors.StorageUnavailableException
import com.printscript.domain.errors.UnsupportedVersionException
import com.printscript.domain.result.FormatOperationResult
import com.printscript.domain.result.InterpretResult
import com.printscript.domain.result.LineStatus
import com.printscript.domain.result.LintIssue
import com.printscript.domain.result.LintResult
import com.printscript.domain.result.OutputLineResult
import com.printscript.domain.result.TestRunResult
import com.printscript.domain.result.ValidationError
import com.printscript.domain.result.ValidationResult
import com.printscript.service.SnippetOperationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

@WebMvcTest(SnippetOperationController::class)
class SnippetOperationControllerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var service: SnippetOperationService

    private fun postJson(path: String, body: String): ResultActions =
        mvc.perform(post("/printscript$path").contentType(MediaType.APPLICATION_JSON).content(body))

    private val rulesBody = """{"snippetKey":"s","rulesKey":"r","version":"1.0"}"""

    // ------------------------------------------------------------ casos felices

    @Test
    fun `interpret devuelve la salida`() {
        `when`(service.interpret("s", "1.0", listOf("a"))).thenReturn(InterpretResult(listOf("hola"), emptyList()))

        postJson("/interpret", """{"snippetKey":"s","version":"1.0","inputs":["a"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.output[0]").value("hola"))
            .andExpect(jsonPath("$.errors").isEmpty)
            // success() es funcion, no tiene que aparecer en el JSON
            .andExpect(jsonPath("$.success").doesNotExist())
    }

    @Test
    fun `los errores del codigo del usuario devuelven 200`() {
        `when`(service.interpret("s", "1.0", null)).thenReturn(InterpretResult(emptyList(), listOf("boom")))

        postJson("/interpret", """{"snippetKey":"s","version":"1.0"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errors[0]").value("boom"))
    }

    @Test
    fun `lint devuelve los issues`() {
        `when`(service.lint("s", "r", "1.0")).thenReturn(
            LintResult(listOf(LintIssue("identifier-format", "WARNING", "mal", 1, 5)), emptyList()),
        )

        postJson("/lint", rulesBody)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.issues[0].rule").value("identifier-format"))
            .andExpect(jsonPath("$.issues[0].column").value(5))
    }

    @Test
    fun `format devuelve la key original y la formateada`() {
        `when`(service.format("s", "r", "1.0")).thenReturn(FormatOperationResult("s", "s.formatted", emptyList()))

        postJson("/format", rulesBody)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.snippetKey").value("s"))
            .andExpect(jsonPath("$.formattedKey").value("s.formatted"))
            .andExpect(jsonPath("$.errors").isEmpty)
            .andExpect(jsonPath("$.formatted").doesNotExist())
    }

    @Test
    fun `format con errores devuelve formattedKey null`() {
        `when`(service.format("s", "r", "1.0")).thenReturn(FormatOperationResult("s", null, listOf("mal")))

        postJson("/format", rulesBody)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.formattedKey").value(null as Any?))
            .andExpect(jsonPath("$.errors[0]").value("mal"))
    }

    @Test
    fun `validate devuelve valid y los errores con regla y posicion`() {
        `when`(service.validate("s", "1.0"))
            .thenReturn(ValidationResult(listOf(ValidationError("syntax", "mal", 3, 12))))

        postJson("/validate", rulesBody)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.errors[0].rule").value("syntax"))
            .andExpect(jsonPath("$.errors[0].message").value("mal"))
            .andExpect(jsonPath("$.errors[0].line").value(3))
            .andExpect(jsonPath("$.errors[0].column").value(12))
    }

    @Test
    fun `validate sin errores devuelve valid true`() {
        `when`(service.validate("s", "1.0")).thenReturn(ValidationResult(emptyList()))

        postJson("/validate", rulesBody)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.errors").isEmpty)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `test devuelve el resultado linea por linea`() {
        `when`(service.runTest("s", "1.1", listOf("a"), listOf("1", "2"))).thenReturn(
            TestRunResult(
                passed = false, total = 2, passedCount = 1, failedCount = 1,
                lines = listOf(
                    OutputLineResult(0, "1", "1", LineStatus.PASS),
                    OutputLineResult(1, "2", null, LineStatus.FAIL),
                ),
                errors = emptyList(),
            ),
        )

        postJson("/test", """{"snippetKey":"s","version":"1.1","inputs":["a"],"expectedOutputs":["1","2"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passed").value(false))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.passedCount").value(1))
            .andExpect(jsonPath("$.failedCount").value(1))
            .andExpect(jsonPath("$.lines[0].status").value("PASS"))
            .andExpect(jsonPath("$.lines[1].index").value(1))
            .andExpect(jsonPath("$.lines[1].expected").value("2"))
            .andExpect(jsonPath("$.lines[1].actual").value(null as Any?))
            .andExpect(jsonPath("$.lines[1].status").value("FAIL"))
            .andExpect(jsonPath("$.errors").isEmpty)
    }

    @Test
    fun `test sin inputs y con outputs vacios explicitos espera que no imprima nada`() {
        `when`(service.runTest("s", "1.0", emptyList(), emptyList()))
            .thenReturn(TestRunResult(true, 0, 0, 0, emptyList(), emptyList()))

        postJson("/test", """{"snippetKey":"s","version":"1.0","expectedOutputs":[]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passed").value(true))
            .andExpect(jsonPath("$.lines").isEmpty)
    }

    @Test
    fun `test sin expectedOutputs devuelve 400 en vez de un falso paso`() {
        postJson("/test", """{"snippetKey":"s","version":"1.0","inputs":[]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
    }

    @Test
    fun `test con expectedOutputs mal escrito devuelve 400`() {
        postJson("/test", """{"snippetKey":"s","version":"1.0","expectedOutput":["1"]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
    }

    @Test
    fun `test sin snippetKey devuelve 400`() {
        postJson("/test", """{"version":"1.0","inputs":[],"expectedOutputs":[]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
    }

    @Test
    fun `test con version en blanco devuelve 400`() {
        postJson("/test", """{"snippetKey":"s","version":"  ","expectedOutputs":[]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
    }

    @Test
    fun `una version no soportada devuelve 4xx`() {
        `when`(service.runTest("s", "9.9", emptyList(), emptyList())).thenThrow(UnsupportedVersionException("9.9"))

        postJson("/test", """{"snippetKey":"s","version":"9.9","expectedOutputs":[]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("UNSUPPORTED_VERSION"))
    }

    @Test
    fun `test de un snippet inexistente devuelve 404`() {
        `when`(service.runTest("s", "1.0", emptyList(), emptyList())).thenThrow(StorageFileNotFoundException("s"))

        postJson("/test", """{"snippetKey":"s","version":"1.0","expectedOutputs":[]}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("FILE_NOT_FOUND"))
    }

    // ------------------------------------------------------ validacion del body

    @Test
    fun `un campo obligatorio faltante devuelve 400`() {
        postJson("/interpret", """{"version":"1.0"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
    }

    @Test
    fun `un campo obligatorio en blanco devuelve 400`() {
        postJson("/lint", """{"snippetKey":"s","version":"  "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
    }

    @Test
    fun `un body que no es JSON devuelve 400`() {
        postJson("/format", "esto no es json")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("El body no es un JSON valido"))
    }

    // ------------------------------------------------- errores del servicio

    private fun expectError(exception: RuntimeException, httpStatus: Int, code: String) {
        `when`(service.validate("s", "1.0")).thenThrow(exception)

        postJson("/validate", rulesBody)
            .andExpect(status().`is`(httpStatus))
            .andExpect(jsonPath("$.error").value(code))
    }

    @Test
    fun `archivo inexistente devuelve 404`() =
        expectError(StorageFileNotFoundException("s"), 404, "FILE_NOT_FOUND")

    @Test
    fun `storage caido devuelve 503`() =
        expectError(StorageUnavailableException("down", null), 503, "STORAGE_UNAVAILABLE")

    @Test
    fun `timeout de ejecucion devuelve 504`() =
        expectError(ExecutionTimeoutException(Duration.ofSeconds(10)), 504, "EXECUTION_TIMEOUT")

    @Test
    fun `version no soportada devuelve 400`() =
        expectError(UnsupportedVersionException("9.9"), 400, "UNSUPPORTED_VERSION")

    @Test
    fun `config de lint invalida devuelve 400`() =
        expectError(InvalidLintConfigException("mal"), 400, "INVALID_LINT_CONFIG")

    @Test
    fun `config de formato invalida devuelve 400`() =
        expectError(InvalidFormatConfigException("mal"), 400, "INVALID_FORMAT_CONFIG")

    @Test
    fun `argumento invalido devuelve 400`() =
        expectError(IllegalArgumentException("mal"), 400, "INVALID_REQUEST")

    @Test
    fun `una ruta inexistente devuelve 404`() {
        mvc.perform(post("/printscript/no-existe").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
    }

    @Test
    fun `un metodo no soportado devuelve 405`() {
        mvc.perform(get("/printscript/lint"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
    }

    @Test
    fun `un error inesperado devuelve 500 sin detalles internos`() {
        `when`(service.validate("s", "1.0")).thenThrow(IllegalStateException("secreto"))

        postJson("/validate", rulesBody)
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("Error interno del servidor"))
    }
}
