package controller.request;

import jakarta.validation.constraints.NotBlank;

/** Request de lint y format. {@code rulesKey} es opcional: sin ella se usan los defaults. */
public record RulesOperationRequest(
        @NotBlank String snippetKey,
        String rulesKey,
        @NotBlank String version) {}