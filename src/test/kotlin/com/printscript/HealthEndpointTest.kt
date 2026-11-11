package com.printscript

import com.printscript.repository.FileRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = ["streams.enabled=false"]) // sin Redis: solo la API HTTP
@AutoConfigureMockMvc
class HealthEndpointTest {

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var fileRepository: FileRepository

    @Test
    fun `health responde UP sin detalles`() {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"status":"UP"}""", true))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist())
    }

    @Test
    fun `ningun otro endpoint de actuator esta expuesto`() {
        listOf("/actuator/env", "/actuator/info", "/actuator/beans", "/actuator/configprops").forEach {
            mvc.perform(get(it)).andExpect(status().is4xxClientError)
        }
    }
}
