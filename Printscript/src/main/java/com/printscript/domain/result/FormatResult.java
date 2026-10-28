package com.printscript.domain.result;

import java.util.List;

public record FormatResult(String formatted, List<String> errors) {

    public boolean success() {
        return errors.isEmpty() && formatted != null;
    }
}
