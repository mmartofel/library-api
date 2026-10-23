package pl.kartofel.library.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import pl.kartofel.library.model.Author;

import java.time.Instant;
import java.time.LocalDate;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record AuthorResponse(@Schema(requiredMode = REQUIRED) Long id,
                             @Schema(requiredMode = REQUIRED) String firstName,
                             @Schema(requiredMode = REQUIRED) String lastName,
                             LocalDate birthDate,
                             String email,
                             @Schema(requiredMode = REQUIRED, accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
                             @Schema(requiredMode = REQUIRED, accessMode = Schema.AccessMode.READ_ONLY) Instant updatedAt,
                             @Schema(requiredMode = REQUIRED) Long version) {

    public static AuthorResponse from(Author a) {
        return new AuthorResponse(a.getId(), a.getFirstName(), a.getLastName(), a.getBirthDate(), a.getEmail(),
                a.getCreatedAt(), a.getUpdatedAt(), a.getVersion());
    }
}
