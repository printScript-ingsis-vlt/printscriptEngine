package com.printscript.controller.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record InterpretRequest(
        @NotBlank String snippetKey,
        @NotBlank String version,
        List<String> inputs) {}