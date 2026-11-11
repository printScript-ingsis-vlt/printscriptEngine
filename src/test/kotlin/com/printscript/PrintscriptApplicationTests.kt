package com.printscript

import com.printscript.repository.FileRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest
class PrintscriptApplicationTests {

    // Se mockea FileRepository para levantar el contexto sin depender del Asset Service.
    @MockitoBean
    lateinit var fileRepository: FileRepository

    @Test
    fun contextLoads() {
        // Pasa si el contexto de Spring levanta sin errores.
    }
}
