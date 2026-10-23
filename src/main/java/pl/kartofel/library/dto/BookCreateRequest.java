package pl.kartofel.library.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import pl.kartofel.library.model.Author;
import pl.kartofel.library.model.Book;

import java.time.LocalDate;

public record BookCreateRequest(@NotBlank @Size(min = 1, max = 200) String title,
                                @Size(min = 10, max = 17) String isbn,
                                @Past LocalDate publishedOn,
                                @Min(1) Integer pages,
                                @NotNull @Schema(description = "Id of the author") Long authorId) {

    public Book toEntity(Author author) {
        Book b = new Book();
        b.setTitle(title);
        b.setIsbn(isbn);
        b.setPublishedOn(publishedOn);
        b.setPages(pages);
        b.setAuthor(author);
        return b;
    }
}
