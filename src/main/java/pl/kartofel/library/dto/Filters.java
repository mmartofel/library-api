package pl.kartofel.library.dto;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.Arrays;

/** Building blocks shared by the list filters. */
final class Filters {

    private Filters() {
    }

    /** Case-insensitive "contains" on any of the attributes; LIKE wildcards in {@code q} match literally. */
    static <T> Specification<T> search(String q, String... attributes) {
        String like = "%" + q.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.or(Arrays.stream(attributes)
                .map(attribute -> cb.like(cb.lower(root.get(attribute)), like, '\\'))
                .toArray(Predicate[]::new));
    }
}
