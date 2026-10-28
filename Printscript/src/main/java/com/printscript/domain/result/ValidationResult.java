package com.printscript.domain.result;

import java.util.List;

public record ValidationResult(List<ValidationError> errors) {

    public boolean valid() {
        return errors.isEmpty();
    }
}