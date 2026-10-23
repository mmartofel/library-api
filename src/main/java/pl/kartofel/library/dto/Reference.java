package pl.kartofel.library.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import pl.kartofel.library.model.Author;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Summary of a related record embedded in a response, so a list never needs one request per row. */
public record Reference(@Schema(requiredMode = REQUIRED) Long id,
                        @Schema(requiredMode = REQUIRED, description = "Display name") String label) {

    public static Reference of(Author a) {
        return a == null ? null : new Reference(a.getId(), a.getDisplayName());
    }
}
