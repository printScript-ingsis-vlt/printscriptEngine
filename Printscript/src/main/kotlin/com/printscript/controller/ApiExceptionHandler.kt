package com.printscript.controller

import com.printscript.domain.errors.ExecutionTimeoutException
import com.printscript.domain.errors.InvalidFormatConfigException
import com.printscript.domain.errors.InvalidLintConfigException
import com.printscript.domain.errors.StorageFileNotFoundException
import com.printscript.domain.errors.StorageUnavailableException
import com.printscript.domain.errors.UnsupportedVersionException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field} ${it.defaultMessage}" }
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "El body no es un JSON valido")

    /** Version del lenguaje no soportada. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun badArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.message)

    @ExceptionHandler(StorageFileNotFoundException::class)
    fun fileNotFound(e: StorageFileNotFoundException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", e.message)

    @ExceptionHandler(StorageUnavailableException::class)
    fun storageDown(e: StorageUnavailableException): ResponseEntity<ErrorResponse> {
        log.error("Storage no disponible", e)
        return build(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "El almacenamiento no esta disponible")
    }

    @ExceptionHandler(ExecutionTimeoutException::class)
    fun timeout(e: ExecutionTimeoutException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.GATEWAY_TIMEOUT, "EXECUTION_TIMEOUT", e.message)

    @ExceptionHandler(UnsupportedVersionException::class)
    fun unsupportedVersion(e: UnsupportedVersionException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "UNSUPPORTED_VERSION", e.message)

    @ExceptionHandler(InvalidLintConfigException::class)
    fun invalidLintConfig(e: InvalidLintConfigException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "INVALID_LINT_CONFIG", e.message)

    @ExceptionHandler(InvalidFormatConfigException::class)
    fun invalidFormatConfig(e: InvalidFormatConfigException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "INVALID_FORMAT_CONFIG", e.message)

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Error inesperado", e)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno del servidor")
    }

    private fun build(status: HttpStatus, code: String, message: String?): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(code, message))
}
