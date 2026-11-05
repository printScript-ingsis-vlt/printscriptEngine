package com.printscript

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PrintscriptApplication

@Suppress("SpreadOperator") // se ejecuta una sola vez al arrancar
fun main(args: Array<String>) {
    runApplication<PrintscriptApplication>(*args)
}
