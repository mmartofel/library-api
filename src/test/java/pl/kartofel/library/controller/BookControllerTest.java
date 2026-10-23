package pl.kartofel.library.controller;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import pl.kartofel.library.config.SecurityConfig;
import pl.kartofel.library.model.Author;
import pl.kartofel.library.model.Book;
import pl.kartofel.library.repository.AuthorRepository;
import pl.kartofel.library.repository.BookRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookController.class)
@Import(SecurityConfig.class)
class BookControllerTest {

    private static final String URL = "/api/books";
    private static final String VALID = """
            {"title":"Solaris","authorId":7}""";
    private static final String UPDATE = """
            {"title":"Solaris","authorId":7,"version":0}""";

    @Autowired
    MockMvc mvc;
    @MockitoBean
    BookRepository repository;
    @MockitoBean
    AuthorRepository authorRepository;

    private static JwtRequestPostProcessor editor() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + SecurityConfig.EDITOR));
    }

    private static JwtRequestPostProcessor viewer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + SecurityConfig.VIEWER));
    }

    private static String create(String extra) {
        return """
                {"title":"Solaris","authorId":7,%s}""".formatted(extra);
    }

    private static Author author(Long id, String firstName, String lastName) {
        Author a = new Author();
        a.setId(id);
        a.setFirstName(firstName);
        a.setLastName(lastName);
        return a;
    }

    private static Author lem() {
        return author(7L, "Stanislaw", "Lem");
    }

    private static Book book(Long id) {
        Book b = new Book();
        b.setId(id);
        b.setTitle("Solaris");
        b.setAuthor(lem());
        ReflectionTestUtils.setField(b, "version", 0L);
        return b;
    }

    private void authorExists() {
        when(authorRepository.findById(7L)).thenReturn(Optional.of(lem()));
    }

    private void saveReturnsArgument() {
        authorExists();
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            Book b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId(1L);
                ReflectionTestUtils.setField(b, "version", 0L);
            }
            return b;
        });
    }

    private void stored(Book b) {
        when(repository.findById(b.getId())).thenReturn(Optional.of(b));
        saveReturnsArgument();
    }

    private void postInvalid(String body, String field, String code) throws Exception {
        authorExists();
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == '%s')].code".formatted(field), contains(code)));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void listBooks() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(book(1L))));
        mvc.perform(get(URL).param("q", "sol").param("authorId", "7").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Solaris"))
                .andExpect(jsonPath("$.content[0].author.id").value(7))
                .andExpect(jsonPath("$.content[0].author.label").value("Stanislaw Lem"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void listBooksInvalidAuthorIdFilter() throws Exception {
        mvc.perform(get(URL).param("authorId", "abc").with(viewer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("authorId"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid"));
        verify(repository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getBook() throws Exception {
        when(repository.findById(1L)).thenReturn(Optional.of(book(1L)));
        mvc.perform(get(URL + "/1").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.author.label").value("Stanislaw Lem"))
                .andExpect(jsonPath("$.isbn").isEmpty())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void getBookNotFound() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(get(URL + "/99").with(viewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Book 99 not found"));
    }

    @Test
    void createBookWithAllFields() throws Exception {
        saveReturnsArgument();
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content(create("""
                        "isbn":"978-0156027601","publishedOn":"1961-06-01","pages":204""")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.isbn").value("978-0156027601"))
                .andExpect(jsonPath("$.publishedOn").value("1961-06-01"))
                .andExpect(jsonPath("$.pages").value(204))
                .andExpect(jsonPath("$.author.id").value(7))
                .andExpect(jsonPath("$.author.label").value("Stanislaw Lem"));
    }

    @Test
    void createBookMinimal() throws Exception {
        saveReturnsArgument();
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isbn").isEmpty())
                .andExpect(jsonPath("$.pages").isEmpty());
    }

    @Test
    void createBookBoundaryValues() throws Exception {
        saveReturnsArgument();
        for (String extra : List.of("\"isbn\":\"0156027607\"", "\"isbn\":\"978-0-15-602760-1\"", "\"pages\":1",
                "\"title\":\"%s\"".formatted("t".repeat(200)))) {
            mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"authorId\":7,\"title\":\"Solaris\"," + extra + "}"))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void createBookMissingAuthor() throws Exception {
        postInvalid("""
                {"title":"Solaris"}""", "authorId", "required");
    }

    @Test
    void createBookUnknownAuthor() throws Exception {
        when(authorRepository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Solaris","authorId":99}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("authorId"))
                .andExpect(jsonPath("$.errors[0].code").value("not-found"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createBookMissingTitle() throws Exception {
        postInvalid("""
                {"authorId":7}""", "title", "required");
        postInvalid("""
                {"title":" ","authorId":7}""", "title", "required");
        postInvalid("""
                {"title":"","authorId":7}""", "title", "required");
    }

    @Test
    void createBookTooLongTitle() throws Exception {
        postInvalid(create("\"title\":\"%s\"".formatted("t".repeat(201))).replace("\"title\":\"Solaris\",", ""),
                "title", "invalid");
    }

    @Test
    void createBookInvalidIsbn() throws Exception {
        postInvalid(create("\"isbn\":\"123456789\""), "isbn", "invalid");
        postInvalid(create("\"isbn\":\"978-0-15-602760-1X\""), "isbn", "invalid");
        postInvalid(create("\"isbn\":\"\""), "isbn", "invalid");
    }

    @Test
    void createBookInvalidPages() throws Exception {
        postInvalid(create("\"pages\":0"), "pages", "invalid");
        postInvalid(create("\"pages\":-5"), "pages", "invalid");
    }

    @Test
    void createBookFuturePublishedOn() throws Exception {
        postInvalid(create("\"publishedOn\":\"%s\"".formatted(LocalDate.now().plusDays(1))), "publishedOn", "past");
    }

    @Test
    void createBookDuplicateIsbn() throws Exception {
        authorExists();
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", new SQLException(), Book.UNIQUE_ISBN)));
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content(create("""
                        "isbn":"0156027607\"""")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("isbn"))
                .andExpect(jsonPath("$.errors[0].code").value("unique"));
    }

    @Test
    void updateBookReplacesRecord() throws Exception {
        Book stored = book(1L);
        stored.setIsbn("0156027607");
        stored.setPages(204);
        stored.setPublishedOn(LocalDate.of(1961, 6, 1));
        stored(stored);
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Solaris (new edition)","authorId":7,"isbn":null,"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Solaris (new edition)"))
                .andExpect(jsonPath("$.isbn").isEmpty())
                .andExpect(jsonPath("$.pages").isEmpty())
                .andExpect(jsonPath("$.publishedOn").isEmpty())
                .andExpect(jsonPath("$.author.id").value(7));
    }

    @Test
    void updateBookChangesAuthor() throws Exception {
        stored(book(1L));
        when(authorRepository.findById(8L)).thenReturn(Optional.of(author(8L, "Joseph", "Conrad")));
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Solaris","authorId":8,"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author.id").value(8))
                .andExpect(jsonPath("$.author.label").value("Joseph Conrad"));
    }

    @Test
    void updateBookWithoutAuthor() throws Exception {
        stored(book(1L));
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Solaris","authorId":null,"version":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("authorId"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateBookUnknownAuthor() throws Exception {
        stored(book(1L));
        when(authorRepository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Solaris","authorId":99,"version":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("authorId"))
                .andExpect(jsonPath("$.errors[0].code").value("not-found"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateBookWithoutVersion() throws Exception {
        stored(book(1L));
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateBookStaleVersion() throws Exception {
        stored(book(1L));
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Solaris","authorId":7,"version":5}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateBookNotFound() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(put(URL + "/99").with(editor()).contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isNotFound());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void deleteBook() throws Exception {
        when(repository.existsById(1L)).thenReturn(true);
        mvc.perform(delete(URL + "/1").with(editor())).andExpect(status().isNoContent());
        verify(repository).deleteById(1L);
    }

    @Test
    void deleteBookNotFound() throws Exception {
        when(repository.existsById(99L)).thenReturn(false);
        mvc.perform(delete(URL + "/99").with(editor())).andExpect(status().isNotFound());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void viewerCannotModify() throws Exception {
        mvc.perform(post(URL).with(viewer()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        mvc.perform(put(URL + "/1").with(viewer()).contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isForbidden());
        mvc.perform(delete(URL + "/1").with(viewer())).andExpect(status().isForbidden());
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void unauthorizedWithoutToken() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        verify(repository, never()).saveAndFlush(any());
    }
}
