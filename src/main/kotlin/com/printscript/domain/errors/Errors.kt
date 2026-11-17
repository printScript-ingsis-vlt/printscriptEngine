package com.printscript.domain.errors

import java.time.Duration

// no existe el archivo con esa key devuelvo 404
class StorageFileNotFoundException(key: String, cause: Throwable? = null) :
    RuntimeException("No existe el archivo: $key", cause)

/** El almacenamiento (Asset Service) no responde o fallo. El controller deberia mapearla a 502/503. */
class StorageUnavailableException(message: String, cause: Throwable?) : RuntimeException(message, cause)

// ejecucion despues del tiempo permitido. El controller deberia mapearla a 408/504.
class ExecutionTimeoutException(limit: Duration, cause: Throwable? = null) :
    RuntimeException("La ejecucion supero el limite de ${limit.toMillis()} ms", cause)

// En una ejecucion interactiva, el programa espero un input mas de lo permitido. Es un error del resultado, no HTTP.
class InputTimeoutException(limit: Duration) :
    RuntimeException("No llego ningun input en ${limit.toMillis()} ms")

// El JSON de reglas de lint es invalido (mal formado o con valores que no corresponden). 400.
class InvalidLintConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// El JSON de reglas de formato es invalido (mal formado o con valores que no corresponden). 400.
class InvalidFormatConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class UnsupportedVersionException(version: String?, cause: Throwable? = null) :
    RuntimeException("Version no soportada: $version", cause)
