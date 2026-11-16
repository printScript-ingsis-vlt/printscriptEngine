package com.printscript.domain.interactive

/**
 * Eventos de una ejecucion interactiva, en el orden en que pasan.
 *
 * Se llaman desde el hilo del interprete (o desde el watchdog, si corta por timeout): quien los implementa
 * no tiene que bloquear mucho, porque el tiempo que tarde cuenta como tiempo de ejecucion.
 */
interface ExecutionListener {

    /** El programa imprimio una linea (println o el prompt de readInput). */
    fun onOutput(line: String)

    /** El programa llamo a readInput y queda esperando un [ExecutionHandle.sendInput]. */
    fun onInputRequested()

    /**
     * Termino: bien (errors vacio) o con errores del codigo del usuario, timeout o falta de input.
     * Se llama una sola vez y nunca despues de [ExecutionHandle.cancel].
     */
    fun onFinished(errors: List<String>)
}

/** Lo que se puede hacer con una ejecucion en curso. */
interface ExecutionHandle {

    /** Entrega un input. Si el programa todavia no lo pidio, queda en cola para el proximo readInput. */
    fun sendInput(value: String)

    /** Corta la ejecucion sin avisar al listener (quien cancela ya sabe). No hace nada si ya termino. */
    fun cancel()
}
