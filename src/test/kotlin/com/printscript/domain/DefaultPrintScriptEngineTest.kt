package com.printscript.domain

import com.printscript.domain.errors.InvalidFormatConfigException
import com.printscript.domain.errors.InvalidLintConfigException
import com.printscript.domain.errors.UnsupportedVersionException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultPrintScriptEngineTest {

    private val engine = DefaultPrintScriptEngine()

    @Nested
    inner class Interpret {

        @Test
        fun `ejecuta el programa y devuelve la salida`() {
            val result = engine.interpret("let x: number = 5;\nprintln(x + 1);\nprintln(\"hola\");", "1.0", null)

            assertEquals(listOf("6", "hola"), result.output)
            assertTrue(result.errors.isEmpty())
            assertTrue(result.success())
        }

        @Test
        fun `un error lexico queda en errors`() {
            val result = engine.interpret("let x: number = 5 # 3;", "1.0", emptyList())

            assertTrue(result.output.isEmpty())
            assertFalse(result.success())
        }

        @Test
        fun `un error de sintaxis queda en errors`() {
            val result = engine.interpret("let x: number = ;", "1.0", emptyList())

            assertFalse(result.success())
        }

        @Test
        fun `un error semantico queda en errors`() {
            val result = engine.interpret("let x: number = \"texto\";", "1.0", emptyList())

            assertFalse(result.success())
        }

        @Test
        fun `readInput consume los inputs en orden`() {
            val code = "let a: string = readInput(\"a\");\nlet b: string = readInput(\"b\");\nprintln(a + b);"

            val result = engine.interpret(code, "1.1", listOf("uno", "dos"))

            assertTrue(result.success(), result.errors.toString())
            assertTrue(result.output.last().endsWith("unodos"), result.output.toString())
        }

        @Test
        fun `readInput sin inputs disponibles devuelve un error`() {
            val result = engine.interpret("let a: string = readInput(\"a\");", "1.1", null)

            assertFalse(result.success())
            assertTrue(result.errors.any { it.contains("readInput sin entrada disponible") }, result.errors.toString())
        }

        @Test
        fun `corta la ejecucion si se supera el maximo de lineas de salida`() {
            val code = "println(1);\n".repeat(10_001)

            val result = engine.interpret(code, "1.0", null)

            assertEquals(10_000, result.output.size)
            assertTrue(result.errors.any { it.contains("maximo") }, result.errors.toString())
        }

        @Test
        fun `una version no soportada lanza UnsupportedVersionException`() {
            assertThrows<UnsupportedVersionException> { engine.interpret("println(1);", "9.9", null) }
        }

        @Test
        fun `readEnv no expone las variables de entorno del contenedor`() {
            val result = engine.interpret("let h: string = readEnv(\"HOME\");\nprintln(h);", "1.1", null)

            assertFalse(result.success())
            assertTrue(result.output.none { it.contains(System.getenv("HOME")) }, result.output.toString())
        }
    }

    @Nested
    inner class Lint {

        @Test
        fun `codigo correcto sin config no tiene issues`() {
            val result = engine.lint("let miVariable: number = 5;", "1.0", null)

            assertTrue(result.success())
        }

        @Test
        fun `sin config pedida no reporta identifier-format`() {
            val result = engine.lint("let mi_variable: number = 5;", "1.0", "")

            assertTrue(result.issues.none { it.rule == "identifier-format" })
        }

        @Test
        fun `camel-case reporta identificadores en snake case con posicion`() {
            val result = engine.lint("let mi_variable: number = 5;", "1.0", """{"identifier-format":"camel-case"}""")

            val issue = result.issues.single()
            assertEquals("identifier-format", issue.rule)
            assertEquals(1, issue.line)
            assertEquals(1, issue.column)
            assertFalse(result.success())
        }

        @Test
        fun `snake-case reporta identificadores en camel case`() {
            val result = engine.lint("let miVariable: number = 5;", "1.0", """{"identifier-format":"snake-case"}""")

            assertEquals("identifier-format", result.issues.single().rule)
        }

        @Test
        fun `un valor invalido de identifier-format lanza InvalidLintConfigException`() {
            assertThrows<InvalidLintConfigException> {
                engine.lint("let x: number = 5;", "1.0", """{"identifier-format":"kebab"}""")
            }
        }

        @Test
        fun `println con expresion se reporta si la regla esta activa`() {
            val result = engine.lint("println(1 + 2);", "1.0", """{"mandatory-variable-or-literal-in-println":true}""")

            assertTrue(result.issues.isNotEmpty())
        }

        @Test
        fun `readInput con expresion se reporta si la regla esta activa`() {
            val result = engine.lint(
                "let a: string = readInput(\"a\" + \"b\");",
                "1.1",
                """{"mandatory-variable-or-literal-in-readInput":true}""",
            )

            assertTrue(result.issues.isNotEmpty(), result.toString())
        }

        @Test
        fun `un error de parseo se devuelve en errors y sin issues`() {
            val result = engine.lint("let x: number = ;", "1.0", null)

            assertTrue(result.issues.isEmpty())
            assertTrue(result.errors.isNotEmpty())
        }

        @Test
        fun `un error semantico se devuelve en errors`() {
            val result = engine.lint("let x: number = \"a\";", "1.0", null)

            assertTrue(result.errors.isNotEmpty())
        }

        @Test
        fun `una config que no es JSON valido lanza InvalidLintConfigException`() {
            assertThrows<InvalidLintConfigException> { engine.lint("let x: number = 5;", "1.0", "{no es json") }
        }

        @Test
        fun `una regla con tipo invalido lanza InvalidLintConfigException`() {
            assertThrows<InvalidLintConfigException> {
                engine.lint("let x: number = 5;", "1.0", """{"identifier-format":123}""")
            }
        }

        @Test
        fun `la config invalida gana aunque el snippet tampoco compile`() {
            assertThrows<InvalidLintConfigException> { engine.lint("let x: number = ;", "1.0", "{no es json") }
        }

        @Test
        fun `una version no soportada lanza UnsupportedVersionException`() {
            assertThrows<UnsupportedVersionException> { engine.lint("println(1);", "", null) }
        }
    }

    @Nested
    inner class Format {

        @Test
        fun `sin config devuelve codigo formateado`() {
            val result = engine.format("let x: number = 5;", "1.0", null)

            assertNotNull(result.formatted)
            assertTrue(result.success())
        }

        @Test
        fun `enforce-spacing-around-equals agrega espacios alrededor del igual`() {
            val result = engine.format("let x:number=5;", "1.0", """{"enforce-spacing-around-equals":true}""")

            assertEquals("let x:number = 5;", result.formatted?.trim())
        }

        @Test
        fun `enforce-no-spacing-around-equals quita espacios alrededor del igual`() {
            val result = engine.format("let x:number = 5;", "1.0", """{"enforce-no-spacing-around-equals":true}""")

            assertEquals("let x:number=5;", result.formatted?.trim())
        }

        @Test
        fun `reglas de los dos puntos`() {
            val config = """
                {"enforce-spacing-before-colon-in-declaration":true,
                 "enforce-spacing-after-colon-in-declaration":true}
            """.trimIndent()

            val result = engine.format("let x:number=5;", "1.0", config)

            assertTrue(result.formatted!!.contains("x : number"), result.formatted)
        }

        @Test
        fun `mandatory-single-space-separation activa los espacios`() {
            val result = engine.format("let x:number=5;", "1.0", """{"mandatory-single-space-separation":true}""")

            assertTrue(result.success(), result.errors.toString())
            assertTrue(result.formatted!!.contains(" = "), result.formatted)
        }

        @Test
        fun `line-breaks-after-println se aplica`() {
            val result = engine.format(
                "let x: number = 5;\nprintln(x);",
                "1.0",
                """{"line-breaks-after-println":1}""",
            )

            assertTrue(result.success(), result.errors.toString())
        }

        @Test
        fun `reglas de if en la version 1_1`() {
            val code = "if (true) { println(1); }"

            val sameLine = engine.format(code, "1.1", """{"if-brace-same-line":true,"indent-inside-if":4}""")
            val belowLine = engine.format(code, "1.1", """{"if-brace-below-line":true,"indent-inside-if":2}""")

            assertTrue(sameLine.success(), sameLine.errors.toString())
            assertTrue(belowLine.success(), belowLine.errors.toString())
            assertTrue(sameLine.formatted!!.contains("    println"), sameLine.formatted)
            assertTrue(belowLine.formatted!!.contains("\n{"), belowLine.formatted)
        }

        @Test
        fun `un error de parseo devuelve formatted null`() {
            val result = engine.format("let x: number = ;", "1.0", null)

            assertNull(result.formatted)
            assertTrue(result.errors.isNotEmpty())
            assertFalse(result.success())
        }

        @Test
        fun `no hace chequeo semantico`() {
            val result = engine.format("let x: number = \"a\";", "1.0", null)

            assertTrue(result.success(), result.errors.toString())
        }

        @Test
        fun `una regla con tipo invalido lanza InvalidFormatConfigException`() {
            assertThrows<InvalidFormatConfigException> {
                engine.format("let x: number = 5;", "1.0", """{"indent-inside-if":"mucho"}""")
            }
        }

        @Test
        fun `una config que no es JSON valido lanza InvalidFormatConfigException`() {
            assertThrows<InvalidFormatConfigException> { engine.format("let x: number = 5;", "1.0", "[1, 2") }
        }
    }

    @Nested
    inner class Validate {

        @Test
        fun `codigo valido no tiene errores`() {
            val result = engine.validate("let x: number = 5;", "1.0")

            assertTrue(result.valid)
        }

        @Test
        fun `un error de sintaxis trae rule syntax y su posicion`() {
            val result = engine.validate("let x: number = 5;\nlet y: number = ;", "1.0")

            assertFalse(result.valid)
            val error = result.errors.single()
            assertEquals("syntax", error.rule)
            assertEquals(2, error.line)
            assertNotNull(error.column)
            assertTrue(error.message.isNotBlank())
        }

        @Test
        fun `un error lexico trae rule lexical y su posicion`() {
            val result = engine.validate("let x: number = 5;\nlet y: number = 5 # 3;", "1.0")

            val error = result.errors.single()
            assertEquals("lexical", error.rule)
            assertEquals(2, error.line)
            assertNotNull(error.column)
        }

        @Test
        fun `un error semantico trae rule semantic y su posicion`() {
            val result = engine.validate("let x: number = 5;\n\nlet y: number = \"a\";", "1.0")

            assertFalse(result.valid)
            val error = result.errors.first()
            assertEquals("semantic", error.rule)
            assertEquals(3, error.line)
            assertNotNull(error.column)
        }

        @Test
        fun `la version 1_1 no es valida en 1_0`() {
            assertFalse(engine.validate("if (true) { println(1); }", "1.0").valid)
        }

        @Test
        fun `una version no soportada lanza UnsupportedVersionException`() {
            assertThrows<UnsupportedVersionException> { engine.validate("println(1);", "abc") }
        }
    }
}
