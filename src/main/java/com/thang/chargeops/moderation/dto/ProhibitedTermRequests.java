package com.thang.chargeops.moderation.dto;

import com.thang.chargeops.moderation.model.*;
import jakarta.validation.constraints.*;
import java.util.Set;

public final class ProhibitedTermRequests {
    private ProhibitedTermRequests() { }
    public record Create(@NotBlank @Size(max = 100) String term, @NotNull TermMatchType matchType,
                         @NotEmpty Set<@NotNull ContentScope> scopes, @NotNull Boolean enabled,
                         @Size(max = 500) String note) { }
    public record Update(@NotBlank @Size(max = 100) String term, @NotNull TermMatchType matchType,
                         @NotEmpty Set<@NotNull ContentScope> scopes, @NotNull Boolean enabled,
                         @Size(max = 500) String note, @NotNull @PositiveOrZero Long version) { }
    public record Status(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long version) { }
    public record Check(@NotNull ContentScope scope, @NotBlank @Size(max = 2000) String text) { }
}
