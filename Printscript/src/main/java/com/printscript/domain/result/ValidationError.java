package com.printscript.domain.result;

public record ValidationError(
        String message,
        Integer line,
        Integer column
) {}