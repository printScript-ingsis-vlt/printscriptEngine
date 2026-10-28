package com.printscript.domain.result;

import java.util.List;

public record LintResult(
        List<LintIssue> issues,
        List<String> errors
) {
    public boolean success() {
        return issues.isEmpty() && errors.isEmpty();
    }
}