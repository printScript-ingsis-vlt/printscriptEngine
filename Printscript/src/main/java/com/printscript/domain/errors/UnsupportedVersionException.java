package com.printscript.domain.errors;

public class UnsupportedVersionException extends RuntimeException {

    public UnsupportedVersionException(String version) {
        super("Version no soportada: " + version);
    }
}