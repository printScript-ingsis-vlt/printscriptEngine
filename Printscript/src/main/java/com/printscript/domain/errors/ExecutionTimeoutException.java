package com.printscript.domain.errors;

// ejecucion despues del tiempo permitido. El controller deberia mapearla a 408/504.
public class ExecutionTimeoutException extends RuntimeException {

    public ExecutionTimeoutException(long seconds) {
        super("La ejecucion supero el limite de " + seconds + " segundos");
    }
}