package com.printscript.domain.result;

import java.util.List;

public record InterpretResult(List<String> output, List<String> errors) {

    public boolean success() {
        return errors.isEmpty();
    }
}
