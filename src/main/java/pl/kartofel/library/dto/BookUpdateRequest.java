package pl.kartofel.library.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Full replacement: optional fields sent as null are cleared. {@code version} must match the stored one. */
public record BookUpdateRequest(@NotBlank @Size(min = 1, max = 200) String title,
                                @Size(min = 10, max = 17) String isbn,
                                @Past LocalDate publishedOn,
                                @Min(1) Integer pages,
                                @NotNull @Schema(description = "Id of the author") Long authorId,
                                @NotNull Long version) {
}
