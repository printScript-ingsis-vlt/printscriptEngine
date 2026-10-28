package com.printscript.domain.result;

public record LintIssue(
        String rule,
        String severity,
        String message,
        int line,
        int column
) {}