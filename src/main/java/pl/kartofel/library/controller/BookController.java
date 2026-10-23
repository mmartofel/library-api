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
import pl.kartofel.library.dto.BookCreateRequest;
import pl.kartofel.library.dto.BookFilter;
import pl.kartofel.library.dto.BookResponse;
import pl.kartofel.library.dto.BookUpdateRequest;
import pl.kartofel.library.model.Author;
import pl.kartofel.library.model.Book;
import pl.kartofel.library.repository.AuthorRepository;
import pl.kartofel.library.repository.BookRepository;

@RestController
@RequestMapping("/api/books")
@Tag(name = "Books")
@SecurityRequirement(name = "bearer")
public class BookController {

    private final BookRepository repository;
    private final AuthorRepository authorRepository;

    public BookController(BookRepository repository, AuthorRepository authorRepository) {
        this.repository = repository;
        this.authorRepository = authorRepository;
    }

    @GetMapping
    public PagedModel<BookResponse> listBooks(@ParameterObject @Valid BookFilter filter,
                                              @ParameterObject Pageable pageable) {
        return new PagedModel<>(repository.findAll(filter.toSpecification(), pageable).map(BookResponse::from));
    }

    @GetMapping("/{id}")
    public BookResponse getBook(@PathVariable Long id) {
        return BookResponse.from(find(id));
    }

    /** Flushed, so a duplicate ISBN fails here and is mapped to 409 instead of failing at commit. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public BookResponse createBook(@Valid @RequestBody BookCreateRequest request) {
        return BookResponse.from(repository.saveAndFlush(request.toEntity(author(request.authorId()))));
    }

    /** Transactional, so the author is still loaded when mapped; flushed, so version and updatedAt are current. */
    @PutMapping("/{id}")
    @Transactional
    public BookResponse updateBook(@PathVariable Long id, @Valid @RequestBody BookUpdateRequest request) {
        Book book = find(id);
        Author author = author(request.authorId());
        if (!request.version().equals(book.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Book.class, id);
        }
        book.replaceWith(request, author);
        return BookResponse.from(repository.saveAndFlush(book));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteBook(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            throw notFound(id);
        }
        repository.deleteById(id);
    }

    private Book find(Long id) {
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    private Author author(Long authorId) {
        return authorRepository.findById(authorId).orElseThrow(() -> ApiExceptionHandler.notFound("authorId"));
    }

    private static ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Book " + id + " not found");
    }
}
