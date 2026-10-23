package pl.kartofel.library.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import pl.kartofel.library.model.Book;

import java.time.Instant;
import java.time.LocalDate;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record BookResponse(@Schema(requiredMode = REQUIRED) Long id,
                           @Schema(requiredMode = REQUIRED) String title,
                           String isbn,
                           LocalDate publishedOn,
                           Integer pages,
                           @Schema(requiredMode = REQUIRED) Reference author,
                           @Schema(requiredMode = REQUIRED, accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
                           @Schema(requiredMode = REQUIRED, accessMode = Schema.AccessMode.READ_ONLY) Instant updatedAt,
                           @Schema(requiredMode = REQUIRED) Long version) {

    public static BookResponse from(Book b) {
        return new BookResponse(b.getId(), b.getTitle(), b.getIsbn(), b.getPublishedOn(), b.getPages(),
                Reference.of(b.getAuthor()), b.getCreatedAt(), b.getUpdatedAt(), b.getVersion());
    }
}
