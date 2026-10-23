package pl.kartofel.library.dto;

import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.data.jpa.domain.Specification;
import pl.kartofel.library.model.Author;

import java.util.ArrayList;
import java.util.List;

/** Optional list filters, combined with AND. */
public record AuthorFilter(
        @Parameter(description = "Case-insensitive search in first name, last name and email") String q) {

    public Specification<Author> toSpecification() {
        List<Specification<Author>> specs = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            specs.add(Filters.search(q, "firstName", "lastName", "email"));
        }
        return Specification.allOf(specs);
    }
}
