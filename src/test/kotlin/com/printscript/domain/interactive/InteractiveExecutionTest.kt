package com.printscript.domain.interactive

import com.printscript.domain.DefaultPrintScriptEngine
import com.printscript.domain.errors.UnsupportedVersionException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class InteractiveExecutionTest {

    private val engine = DefaultPrintScriptEngine()

    @Test
    fun `el output sale antes de pedir el input y el input destraba el programa`() {
        val events = RecordingListener()
        val code = "println(\"hola\");\nlet a: string = readInput(\"\");\nprintln(a + \"!\");"

        val handle = engine.interpretInteractive(code, "1.1", events)

        assertEquals(Event.Output("hola"), events.next())
        assertEquals(Event.InputRequested, events.next())
        handle.sendInput("uno")
        assertEquals(Event.Output("uno!"), events.next())
        assertEquals(Event.Finished(emptyList()), events.next())
        events.assertNoMore()
    }

    @Test
    fun `el prompt de readInput sale como output antes del pedido`() {
        val events = RecordingListener()

        val handle = engine.interpretInteractive("let a: string = readInput(\"Nombre:\");\nprintln(a);", "1.1", events)

        val beforeRequest = events.untilInputRequested()
        assertTrue(beforeRequest.any { it.contains("Nombre:") }, beforeRequest.toString())
        handle.sendInput("Ada")
        assertEquals(Event.Output("Ada"), events.next())
        assertEquals(Event.Finished(emptyList()), events.next())
    }

    @Test
    fun `los inputs mandados antes de tiempo quedan en cola`() {
        val events = RecordingListener()
        val code = "let a: string = readInput(\"\");\nlet b: string = readInput(\"\");\nprintln(a + b);"
        val gate = SlowStartListener(events)

        val handle = engine.interpretInteractive(code, "1.1", gate)
        handle.sendInput("uno")
        handle.sendInput("dos")

        assertEquals(Event.Output("unodos"), events.nextOutput())
        assertEquals(Event.Finished(emptyList()), events.next())
    }

    @Test
    fun `el tiempo esperando input no cuenta para el timeout de ejecucion`() {
        // 1s alcanza para llegar al readInput aun con el JVM frio; la espera de 1.5s lo superaria si contara.
        val impatient = DefaultPrintScriptEngine(executionTimeout = Duration.ofSeconds(1))
        val events = RecordingListener()

        val handle = impatient.interpretInteractive("let a: string = readInput(\"\");\nprintln(a);", "1.1", events)

        assertEquals(Event.InputRequested, events.next())
        Thread.sleep(1_500)
        handle.sendInput("tarde")
        assertEquals(Event.Output("tarde"), events.next())
        assertEquals(Event.Finished(emptyList()), events.next())
    }

    @Test
    fun `corta por timeout si el programa corre demasiado`() {
        val impatient = DefaultPrintScriptEngine(executionTimeout = Duration.ofMillis(100))
        // El tiempo que tarda el listener corre en el hilo del interprete: cuenta como ejecucion.
        val slow = object : ExecutionListener by RecordingListener() {
            val finished = LinkedBlockingQueue<List<String>>()
            override fun onOutput(line: String) = Thread.sleep(30)
            override fun onFinished(errors: List<String>) {
                finished.add(errors)
            }
        }

        impatient.interpretInteractive("println(1);\n".repeat(50), "1.0", slow)

        val errors = slow.finished.poll(5, TimeUnit.SECONDS) ?: fail("No termino")
        assertTrue(errors.single().contains("limite"), errors.toString())
        assertNull(slow.finished.poll(300, TimeUnit.MILLISECONDS), "onFinished se llamo dos veces")
    }

    @Test
    fun `corta si el input no llega a tiempo`() {
        val hurried = DefaultPrintScriptEngine(inputTimeout = Duration.ofMillis(100))
        val events = RecordingListener()

        hurried.interpretInteractive("let a: string = readInput(\"\");", "1.1", events)

        assertEquals(Event.InputRequested, events.next())
        val finished = events.next() as Event.Finished
        assertTrue(finished.errors.single().contains("input"), finished.errors.toString())
    }

    @Test
    fun `cancel corta la ejecucion sin avisar al listener`() {
        val events = RecordingListener()

        val handle = engine.interpretInteractive("let a: string = readInput(\"\");\nprintln(a);", "1.1", events)

        assertEquals(Event.InputRequested, events.next())
        handle.cancel()
        handle.sendInput("ya no")
        events.assertNoMore()
    }

    @Test
    fun `un error de sintaxis termina con el error y sin pedir input`() {
        val events = RecordingListener()

        engine.interpretInteractive("let x: number = ;", "1.0", events)

        val finished = events.next() as Event.Finished
        assertTrue(finished.errors.isNotEmpty())
        events.assertNoMore()
    }

    @Test
    fun `una version invalida falla antes de arrancar`() {
        assertThrows<UnsupportedVersionException> {
            engine.interpretInteractive("println(1);", "9.9", RecordingListener())
        }
    }

    // ------------------------------------------------------------------ helpers

    sealed interface Event {
        data class Output(val line: String) : Event
        data object InputRequested : Event
        data class Finished(val errors: List<String>) : Event
    }

    /** Guarda los eventos en orden y deja esperarlos con timeout. */
    class RecordingListener : ExecutionListener {
        private val events = LinkedBlockingQueue<Event>()

        override fun onOutput(line: String) {
            events.add(Event.Output(line))
        }

        override fun onInputRequested() {
            events.add(Event.InputRequested)
        }

        override fun onFinished(errors: List<String>) {
            events.add(Event.Finished(errors))
        }

        fun next(): Event = events.poll(5, TimeUnit.SECONDS) ?: fail("No llego ningun evento")

        /** Siguiente output, salteando los pedidos de input. */
        fun nextOutput(): Event {
            while (true) {
                val event = next()
                if (event !is Event.InputRequested) return event
            }
        }

        /** Las lineas impresas hasta el primer pedido de input. */
        fun untilInputRequested(): List<String> {
            val lines = mutableListOf<String>()
            while (true) {
                when (val event = next()) {
                    is Event.Output -> lines.add(event.line)
                    Event.InputRequested -> return lines
                    is Event.Finished -> fail("Termino sin pedir input: $event")
                }
            }
        }

        fun assertNoMore() {
            assertNull(events.poll(300, TimeUnit.MILLISECONDS), "Llego un evento de mas")
        }
    }

    /** Demora el primer pedido de input, para que los inputs lleguen antes de que el programa los pida. */
    class SlowStartListener(private val delegate: ExecutionListener) : ExecutionListener by delegate {
        @Volatile private var first = true

        override fun onInputRequested() {
            if (first) {
                first = false
                Thread.sleep(100)
            }
            delegate.onInputRequested()
        }
    }
}
