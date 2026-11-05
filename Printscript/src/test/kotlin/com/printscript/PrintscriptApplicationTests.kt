package com.printscript

import com.printscript.repository.FileRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest
class PrintscriptApplicationTests {

    // Todavia no hay implementacion real de FileRepository (S3); se mockea para levantar el contexto.
    @MockitoBean
    lateinit var fileRepository: FileRepository

    @Test
    fun contextLoads() {
    }
}
