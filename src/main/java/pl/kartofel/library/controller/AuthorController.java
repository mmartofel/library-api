package pl.kartofel.library.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.kartofel.library.dto.AuthorCreateRequest;
import pl.kartofel.library.dto.AuthorFilter;
import pl.kartofel.library.dto.AuthorResponse;
import pl.kartofel.library.dto.AuthorUpdateRequest;
import pl.kartofel.library.model.Author;
import pl.kartofel.library.repository.AuthorRepository;
import pl.kartofel.library.repository.BookRepository;

@RestController
@RequestMapping("/api/authors")
@Tag(name = "Authors")
@SecurityRequirement(name = "bearer")
public class AuthorController {

    private final AuthorRepository repository;
    private final BookRepository bookRepository;

    public AuthorController(AuthorRepository repository, BookRepository bookRepository) {
        this.repository = repository;
        this.bookRepository = bookRepository;
    }

    @GetMapping
    public PagedModel<AuthorResponse> listAuthors(@ParameterObject @Valid AuthorFilter filter,
                                                  @ParameterObject Pageable pageable) {
        return new PagedModel<>(repository.findAll(filter.toSpecification(), pageable).map(AuthorResponse::from));
    }

    @GetMapping("/{id}")
    public AuthorResponse getAuthor(@PathVariable Long id) {
        return AuthorResponse.from(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorResponse createAuthor(@Valid @RequestBody AuthorCreateRequest request) {
        return AuthorResponse.from(repository.save(request.toEntity()));
    }

    /** Flushed, so version and updatedAt in the response are current. */
    @PutMapping("/{id}")
    @Transactional
    public AuthorResponse updateAuthor(@PathVariable Long id, @Valid @RequestBody AuthorUpdateRequest request) {
        Author author = find(id);
        if (!request.version().equals(author.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Author.class, id);
        }
        author.replaceWith(request);
        return AuthorResponse.from(repository.saveAndFlush(author));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteAuthor(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            throw notFound(id);
        }
        long books = bookRepository.countByAuthorId(id);
        if (books > 0) {
            throw ApiExceptionHandler.inUse("Author " + id + " has " + books
                    + (books == 1 ? " book" : " books") + "; reassign or delete them first", "still referenced by books");
        }
        repository.deleteById(id);
    }

    private Author find(Long id) {
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    private static ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Author " + id + " not found");
    }
}
