package com.printscript.domain.errors;


// no existe el archivo con esa key devuelvo 404
public class StorageFileNotFoundException extends RuntimeException {

    public StorageFileNotFoundException(String key) {
        super("No existe el archivo: " + key);
    }
}