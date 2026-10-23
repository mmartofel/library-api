package pl.kartofel.library.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Full replacement: optional fields sent as null are cleared. {@code version} must match the stored one. */
public record AuthorUpdateRequest(@NotBlank @Size(min = 1, max = 100) String firstName,
                                  @NotBlank @Size(min = 1, max = 100) String lastName,
                                  @Past LocalDate birthDate,
                                  @Email @Size(max = 255) String email,
                                  @NotNull Long version) {
}
