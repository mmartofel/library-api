package pl.kartofel.library.dto;

import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.data.jpa.domain.Specification;
import pl.kartofel.library.model.Book;

import java.util.ArrayList;
import java.util.List;

/** Optional list filters, combined with AND. */
public record BookFilter(
        @Parameter(description = "Case-insensitive search in title and ISBN") String q,
        @Parameter(description = "Id of the author") Long authorId) {

    public Specification<Book> toSpecification() {
        List<Specification<Book>> specs = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            specs.add(Filters.search(q, "title", "isbn"));
        }
        if (authorId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("author").get("id"), authorId));
        }
        return Specification.allOf(specs);
    }
}
