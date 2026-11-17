package com.printscript.domain.interactive

import com.printscript.domain.errors.ExecutionTimeoutException
import com.printscript.domain.errors.InputTimeoutException
import interpreter.Output
import interpreter.ScriptInputProvider
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Una ejecucion con inputs a demanda. El interprete corre en un hilo propio y:
 * - cada `Output.write` se pasa al listener en el momento (output en vivo);
 * - `readLine()` avisa `onInputRequested` y se bloquea en una cola hasta que llega un [sendInput].
 *
 * Limites:
 * - [executionTimeout] cuenta solo el tiempo corriendo: el tiempo esperando input no suma, si no un usuario lento
 *   daria timeout. Lo controla un watchdog cada [CHECK_INTERVAL_MS].
 * - [inputTimeout] es cuanto se espera cada input antes de cortar.
 * - [maxOutputLines], igual que en la ejecucion normal.
 */
class InteractiveExecution(
    private val listener: ExecutionListener,
    private val executionTimeout: Duration,
    private val inputTimeout: Duration,
    private val maxOutputLines: Int,
) : ExecutionHandle {

    private val inputs = LinkedBlockingQueue<String>()
    private val done = AtomicBoolean(false)
    private val clock = RunningClock()

    @Volatile private var future: Future<*>? = null

    @Volatile private var watchdog: ScheduledFuture<*>? = null

    // Solo lo toca el hilo del interprete.
    private var linesWritten = 0

    val output: Output = object : Output {
        override fun write(text: String) {
            // Si ya se corto (cancel o timeout), se frena el programa en el proximo println.
            check(!done.get()) { "Ejecucion cortada" }
            check(linesWritten < maxOutputLines) { "Se supero el maximo de $maxOutputLines lineas de salida" }
            linesWritten++
            listener.onOutput(text)
        }
    }

    val input: ScriptInputProvider = object : ScriptInputProvider {
        override fun readLine(): String {
            clock.pause()
            try {
                listener.onInputRequested()
                return inputs.poll(inputTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    ?: throw InputTimeoutException(inputTimeout)
            } finally {
                clock.resume()
            }
        }
    }

    /**
     * Arranca [body] (que corre el pipeline con [output] e [input] y devuelve los errores) en [executor].
     * El watchdog se agenda antes del submit: asi, si el programa termina enseguida, ya se lo puede cancelar.
     */
    fun start(
        executor: ExecutorService,
        scheduler: ScheduledExecutorService,
        body: (Output, ScriptInputProvider) -> List<String>,
    ) {
        clock.resume()
        watchdog = scheduler.scheduleAtFixedRate(
            ::checkTimeout,
            CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        future = executor.submit {
            val errors = body(output, input)
            // Termino solo: no se interrumpe el propio hilo (el listener todavia tiene que mandar el resultado).
            if (done.compareAndSet(false, true)) {
                watchdog?.cancel(false)
                listener.onFinished(errors)
            }
        }
    }

    override fun sendInput(value: String) {
        inputs.offer(value)
    }

    override fun cancel() {
        if (done.compareAndSet(false, true)) stop()
    }

    // Corre en el hilo del watchdog. Solo el primero que llega gana: el fin normal, el timeout o el cancel.
    private fun checkTimeout() {
        if (clock.elapsed() > executionTimeout && done.compareAndSet(false, true)) {
            stop()
            listener.onFinished(listOf(ExecutionTimeoutException(executionTimeout).message.orEmpty()))
        }
    }

    // Frena el watchdog e interrumpe el hilo del interprete (si estaba esperando input, sale del poll).
    private fun stop() {
        watchdog?.cancel(false)
        future?.cancel(true)
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 50L
    }
}

/** Cronometro que se puede pausar: mide solo el tiempo en que el programa esta corriendo. */
private class RunningClock {
    private var accumulatedNanos = 0L
    private var runningSince: Long? = null

    @Synchronized
    fun resume() {
        if (runningSince == null) runningSince = System.nanoTime()
    }

    @Synchronized
    fun pause() {
        runningSince?.let { accumulatedNanos += System.nanoTime() - it }
        runningSince = null
    }

    @Synchronized
    fun elapsed(): Duration =
        Duration.ofNanos(accumulatedNanos + (runningSince?.let { System.nanoTime() - it } ?: 0L))
}
