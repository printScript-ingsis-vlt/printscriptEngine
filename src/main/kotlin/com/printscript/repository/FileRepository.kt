package com.printscript.repository

/**
 * Acceso al almacenamiento de archivos (Asset Service). Devuelve datos crudos, sin logica del lenguaje.
 * Lanza StorageFileNotFoundException si la key no existe y
 * StorageUnavailableException si el bucket no responde.
 */
interface FileRepository {

    fun get(key: String): String

    fun put(key: String, content: String)
}
