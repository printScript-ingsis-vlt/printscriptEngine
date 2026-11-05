package com.printscript.domain.errors

// no existe el archivo con esa key devuelvo 404
class StorageFileNotFoundException(key: String) : RuntimeException("No existe el archivo: $key")

/** El almacenamiento (S3) no responde o fallo. El controller deberia mapearla a 502/503. */
class StorageUnavailableException(message: String, cause: Throwable?) : RuntimeException(message, cause)

// ejecucion despues del tiempo permitido. El controller deberia mapearla a 408/504.
class ExecutionTimeoutException(seconds: Long, cause: Throwable? = null) :
    RuntimeException("La ejecucion supero el limite de $seconds segundos", cause)

// El JSON de reglas de lint es invalido (mal formado o con valores que no corresponden). 400.
class InvalidLintConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// El JSON de reglas de formato es invalido (mal formado o con valores que no corresponden). 400.
class InvalidFormatConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class UnsupportedVersionException(version: String?, cause: Throwable? = null) :
    RuntimeException("Version no soportada: $version", cause)
