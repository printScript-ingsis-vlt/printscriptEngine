package com.printscript.domain.errors

// no existe el archivo con esa key devuelvo 404
class StorageFileNotFoundException(key: String) : RuntimeException("No existe el archivo: $key")

/** El almacenamiento (S3) no responde o fallo. El controller deberia mapearla a 502/503. */
class StorageUnavailableException(message: String, cause: Throwable?) : RuntimeException(message, cause)

// ejecucion despues del tiempo permitido. El controller deberia mapearla a 408/504.
class ExecutionTimeoutException(seconds: Long) :
    RuntimeException("La ejecucion supero el limite de $seconds segundos")

class InvalidLintConfigException(message: String) : RuntimeException(message)

class UnsupportedVersionException(version: String?) : RuntimeException("Version no soportada: $version")
