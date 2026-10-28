package com.printscript.domain.errors;

public class InvalidLintConfigException extends RuntimeException {

    public InvalidLintConfigException(String message) {
        super(message);
    }
}