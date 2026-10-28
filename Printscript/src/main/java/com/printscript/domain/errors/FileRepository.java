package com.printscript.domain.errors;

/**
 * Acceso al almacenamiento de archivos (S3). Devuelve datos crudos, sin logica del lenguaje.
 * Lanza {@link StorageFileNotFoundException} si la key no existe y
 * {@link StorageUnavailableException} si el bucket no responde.
 */
public interface FileRepository {

    String get(String key);

    void put(String key, String content);
}
