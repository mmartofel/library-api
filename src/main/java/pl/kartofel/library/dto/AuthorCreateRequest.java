package pl.kartofel.library.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import pl.kartofel.library.model.Author;

import java.time.LocalDate;

public record AuthorCreateRequest(@NotBlank @Size(min = 1, max = 100) String firstName,
                                  @NotBlank @Size(min = 1, max = 100) String lastName,
                                  @Past LocalDate birthDate,
                                  @Email @Size(max = 255) String email) {

    public Author toEntity() {
        Author a = new Author();
        a.setFirstName(firstName);
        a.setLastName(lastName);
        a.setBirthDate(birthDate);
        a.setEmail(email);
        return a;
    }
}
