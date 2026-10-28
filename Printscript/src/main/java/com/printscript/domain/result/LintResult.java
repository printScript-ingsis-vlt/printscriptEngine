package com.printscript.domain.result;

import java.util.List;

public record LintResult(List<String> issues) {

    public boolean clean() {
        return issues.isEmpty();
    }
}