package pl.kartofel.library;

import com.jayway.jsonpath.JsonPath;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pl.kartofel.library.config.SecurityConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LibraryApplicationIT {

    private static final String AUTHORS = "/api/authors";
    private static final String BOOKS = "/api/books";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    MockMvc mvc;

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    private static JwtRequestPostProcessor editor() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + SecurityConfig.EDITOR));
    }

    private String createAuthor(String firstName, String lastName, String email) throws Exception {
        String emailJson = email == null ? "null" : "\"" + email + "\"";
        return created(AUTHORS, """
                {"firstName":"%s","lastName":"%s","birthDate":"1950-05-01","email":%s}"""
                .formatted(firstName, lastName, emailJson));
    }

    private String createBook(String body) throws Exception {
        return created(BOOKS, body);
    }

    private String created(String url, String body) throws Exception {
        String response = mvc.perform(post(url).with(editor()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id").toString();
    }

    private ResultActions listBooks(String param, String value) throws Exception {
        return mvc.perform(get(BOOKS).param(param, value).param("sort", "title").with(editor()))
                .andExpect(status().isOk());
    }

    @Test
    void authorRoundTrip() throws Exception {
        String id = createAuthor("Anna", "Roundtrip", "anna-roundtrip@example.com");

        String body = mvc.perform(get(AUTHORS + "/" + id).with(editor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Roundtrip"))
                .andExpect(jsonPath("$.birthDate").value("1950-05-01"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        Instant createdAt = Instant.parse(JsonPath.read(body, "$.createdAt"));
        Instant updatedAt = Instant.parse(JsonPath.read(body, "$.updatedAt"));

        body = mvc.perform(put(AUTHORS + "/" + id).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Anna","lastName":"Changed","birthDate":null,"email":null,"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.birthDate").isEmpty())
                .andExpect(jsonPath("$.email").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.<String>read(body, "$.createdAt"))).isEqualTo(createdAt);
        assertThat(Instant.parse(JsonPath.<String>read(body, "$.updatedAt"))).isAfter(updatedAt);

        mvc.perform(put(AUTHORS + "/" + id).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Anna","lastName":"Stale","version":0}"""))
                .andExpect(status().isConflict());
        mvc.perform(delete(AUTHORS + "/" + id).with(editor())).andExpect(status().isNoContent());
        mvc.perform(get(AUTHORS + "/" + id).with(editor())).andExpect(status().isNotFound());
    }

    @Test
    void duplicateEmailConflictButManyAuthorsWithoutEmail() throws Exception {
        createAuthor("Jan", "Unique", "jan-unique@example.com");
        mvc.perform(post(AUTHORS).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Other","lastName":"Unique","email":"jan-unique@example.com"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("unique"));
        createAuthor("No", "Email", null);
        createAuthor("Blank", "Email", "");
        createAuthor("Also", "Noemail", null);
    }

    @Test
    void authorPagingAndSearch() throws Exception {
        createAuthor("Zofia", "Searchable", "zofia@example.com");
        createAuthor("Zenon", "Searchable", "zenon@example.com");

        mvc.perform(get(AUTHORS).param("q", "searchABLE").param("size", "1").param("sort", "firstName").with(editor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].firstName").value("Zenon"))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        mvc.perform(get(AUTHORS).param("sort", "unknown").with(editor()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bookRoundTrip() throws Exception {
        String authorId = createAuthor("Bruno", "Writer", "bruno-writer@example.com");
        String id = createBook("""
                {"title":"Roundtrip book","isbn":"978-0000000001","publishedOn":"1934-01-01","pages":320,
                 "authorId":%s}""".formatted(authorId));

        String body = mvc.perform(get(BOOKS + "/" + id).with(editor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value("978-0000000001"))
                .andExpect(jsonPath("$.publishedOn").value("1934-01-01"))
                .andExpect(jsonPath("$.pages").value(320))
                .andExpect(jsonPath("$.author.id").value(Integer.parseInt(authorId)))
                .andExpect(jsonPath("$.author.label").value("Bruno Writer"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        Instant updatedAt = Instant.parse(JsonPath.read(body, "$.updatedAt"));

        body = mvc.perform(put(BOOKS + "/" + id).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Roundtrip book","authorId":%s,"version":0}""".formatted(authorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.isbn").isEmpty())
                .andExpect(jsonPath("$.pages").isEmpty())
                .andExpect(jsonPath("$.author.label").value("Bruno Writer"))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.<String>read(body, "$.updatedAt"))).isAfter(updatedAt);

        mvc.perform(put(BOOKS + "/" + id).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Stale","authorId":%s,"version":0}""".formatted(authorId)))
                .andExpect(status().isConflict());
        mvc.perform(delete(BOOKS + "/" + id).with(editor())).andExpect(status().isNoContent());
        mvc.perform(get(BOOKS + "/" + id).with(editor())).andExpect(status().isNotFound());
    }

    @Test
    void bookAuthorChanges() throws Exception {
        String first = createAuthor("First", "Author", "first-author@example.com");
        String second = createAuthor("Second", "Author", "second-author@example.com");
        String id = createBook("""
                {"title":"Moved book","authorId":%s}""".formatted(first));

        mvc.perform(put(BOOKS + "/" + id).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Moved book","authorId":%s,"version":0}""".formatted(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author.id").value(Integer.parseInt(second)))
                .andExpect(jsonPath("$.author.label").value("Second Author"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(BOOKS + "/" + id).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Moved book","authorId":%s,"version":1}""".formatted(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author.label").value("First Author"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(BOOKS + "/" + id).with(editor()))
                .andExpect(jsonPath("$.author.label").value("First Author"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(BOOKS + "/" + id).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Moved book","authorId":null,"version":2}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("authorId"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));
    }

    @Test
    void duplicateIsbnConflict() throws Exception {
        String authorId = createAuthor("Isbn", "Owner", "isbn-owner@example.com");
        createBook("""
                {"title":"Original","isbn":"978-1111111111","authorId":%s}""".formatted(authorId));
        mvc.perform(post(BOOKS).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Copy","isbn":"978-1111111111","authorId":%s}""".formatted(authorId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("isbn"))
                .andExpect(jsonPath("$.errors[0].code").value("unique"));
    }

    @Test
    void bookFilters() throws Exception {
        String alice = createAuthor("Alice", "Filter", "alice-filter@example.com");
        String bob = createAuthor("Bob", "Filter", "bob-filter@example.com");
        createBook("""
                {"title":"BF1 alpha","isbn":"BF-0000000001","authorId":%s}""".formatted(alice));
        createBook("""
                {"title":"BF2 beta","authorId":%s}""".formatted(bob));
        createBook("""
                {"title":"BF3 100%%_done","authorId":%s}""".formatted(alice));

        listBooks("q", "bf").andExpect(jsonPath("$.content[*].title", contains("BF1 alpha", "BF2 beta", "BF3 100%_done")));
        listBooks("q", "bf-000").andExpect(jsonPath("$.content[*].title", contains("BF1 alpha")));
        listBooks("authorId", alice).andExpect(jsonPath("$.content[*].title", contains("BF1 alpha", "BF3 100%_done")));
        mvc.perform(get(BOOKS).param("q", "beta").param("authorId", alice).with(editor()))
                .andExpect(jsonPath("$.content").isEmpty());
        listBooks("q", "100%_DONE").andExpect(jsonPath("$.content[*].title", contains("BF3 100%_done")));
        listBooks("q", "BF%beta").andExpect(jsonPath("$.content").isEmpty());
        listBooks("authorId", "999999").andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get(BOOKS).param("sort", "author.lastName").param("q", "bf").with(editor()))
                .andExpect(status().isOk());
    }

    @Test
    void bookUnknownAuthor() throws Exception {
        mvc.perform(post(BOOKS).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Ghost","authorId":999999}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("authorId"))
                .andExpect(jsonPath("$.errors[0].code").value("not-found"));
        String authorId = createAuthor("Real", "Author", "real-author@example.com");
        String id = createBook("""
                {"title":"Ghost later","authorId":%s}""".formatted(authorId));
        mvc.perform(put(BOOKS + "/" + id).with(editor()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Ghost later","authorId":999999,"version":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("authorId"))
                .andExpect(jsonPath("$.errors[0].code").value("not-found"));
    }

    @Test
    void deletingAuthorWithBooksIsRefused() throws Exception {
        String authorId = createAuthor("Ivan", "Inuse", "ivan-inuse@example.com");
        String bookId = createBook("""
                {"title":"Keeps Ivan","authorId":%s}""".formatted(authorId));

        mvc.perform(delete(AUTHORS + "/" + authorId).with(editor()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Author " + authorId + " has 1 book; reassign or delete them first"))
                .andExpect(jsonPath("$.errors[0].code").value("in-use"))
                .andExpect(jsonPath("$.errors[0].field").doesNotExist());
        mvc.perform(get(AUTHORS + "/" + authorId).with(editor())).andExpect(status().isOk());
        mvc.perform(get(BOOKS + "/" + bookId).with(editor())).andExpect(jsonPath("$.author.label").value("Ivan Inuse"));

        mvc.perform(delete(BOOKS + "/" + bookId).with(editor())).andExpect(status().isNoContent());
        mvc.perform(delete(AUTHORS + "/" + authorId).with(editor())).andExpect(status().isNoContent());
    }

    @Test
    void databaseGuardsBooks() {
        Long authorId = jdbc.queryForObject("""
                insert into author (first_name, last_name, created_at, updated_at, version)
                values ('Db', 'Guard', now(), now(), 0) returning id""", Long.class);
        jdbc.update("""
                insert into book (title, author_id, created_at, updated_at, version)
                values ('Guarded', ?, now(), now(), 0)""", authorId);
        assertThatThrownBy(() -> jdbc.update("delete from author where id = ?", authorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_book_author");
        assertThatThrownBy(() -> jdbc.update("""
                insert into book (title, created_at, updated_at, version)
                values ('No author', now(), now(), 0)"""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("author_id");
        assertThatThrownBy(() -> jdbc.update("""
                insert into book (title, pages, author_id, created_at, updated_at, version)
                values ('Bad pages', 0, ?, now(), now(), 0)""", authorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_book_pages");
        assertThatThrownBy(() -> jdbc.update("""
                insert into book (title, isbn, author_id, created_at, updated_at, version)
                values ('Bad isbn', '123', ?, now(), now(), 0)""", authorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_book_isbn_length");
    }

    @Test
    void migrationsApplied() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void unauthorizedWithoutToken() throws Exception {
        mvc.perform(get(AUTHORS)).andExpect(status().isUnauthorized());
        mvc.perform(get(BOOKS)).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /** Regenerates the committed API contract consumed by the Web UI. */
    @Test
    void exportOpenApiContract() throws Exception {
        String spec = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Path file = Path.of("contract/openapi.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, spec + System.lineSeparator());

        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.info.title").value("Library API"))
                .andExpect(jsonPath("$.paths['/api/authors'].get.operationId").value("listAuthors"))
                .andExpect(jsonPath("$.paths['/api/authors'].post.operationId").value("createAuthor"))
                .andExpect(jsonPath("$.paths['/api/authors/{id}'].get.operationId").value("getAuthor"))
                .andExpect(jsonPath("$.paths['/api/authors/{id}'].put.operationId").value("updateAuthor"))
                .andExpect(jsonPath("$.paths['/api/authors/{id}'].delete.operationId").value("deleteAuthor"))
                .andExpect(jsonPath("$.paths['/api/books'].get.operationId").value("listBooks"))
                .andExpect(jsonPath("$.paths['/api/books'].post.operationId").value("createBook"))
                .andExpect(jsonPath("$.paths['/api/books/{id}'].get.operationId").value("getBook"))
                .andExpect(jsonPath("$.paths['/api/books/{id}'].put.operationId").value("updateBook"))
                .andExpect(jsonPath("$.paths['/api/books/{id}'].delete.operationId").value("deleteBook"))
                .andExpect(jsonPath("$.paths['/api/authors/{id}'].get.responses['200'].content['application/json']").exists())
                .andExpect(jsonPath("$.paths['/api/authors'].get.parameters[*].name", hasItems("q", "page", "size", "sort")))
                .andExpect(jsonPath("$.paths['/api/books'].get.parameters[*].name",
                        hasItems("q", "authorId", "page", "size", "sort")))
                .andExpect(jsonPath("$.paths['/api/books'].get.responses['400']").exists())
                .andExpect(jsonPath("$.components.schemas.AuthorResponse.required", containsInAnyOrder(
                        "id", "firstName", "lastName", "createdAt", "updatedAt", "version")))
                .andExpect(jsonPath("$.components.schemas.BookResponse.required", containsInAnyOrder(
                        "id", "title", "author", "createdAt", "updatedAt", "version")))
                .andExpect(jsonPath("$.components.schemas.BookCreateRequest.required",
                        containsInAnyOrder("title", "authorId")))
                .andExpect(jsonPath("$.components.schemas.BookCreateRequest.properties.title.minLength").value(1))
                .andExpect(jsonPath("$.components.schemas.BookUpdateRequest.properties.title.minLength").value(1))
                .andExpect(jsonPath("$.components.schemas.AuthorCreateRequest.properties.firstName.minLength").value(1))
                .andExpect(jsonPath("$.components.schemas.AuthorUpdateRequest.properties.lastName.minLength").value(1))
                .andExpect(jsonPath("$.components.schemas.BookCreateRequest.properties.isbn.minLength").value(10))
                .andExpect(jsonPath("$.components.schemas.BookCreateRequest.properties.pages.minimum").value(1))
                .andExpect(jsonPath("$.components.schemas.BookUpdateRequest.required",
                        containsInAnyOrder("title", "authorId", "version")))
                .andExpect(jsonPath("$.components.schemas.Reference.required", containsInAnyOrder("id", "label")))
                .andExpect(jsonPath("$.paths['/api/books'].post.responses['409'].content['application/problem+json'].schema['$ref']")
                        .value("#/components/schemas/Problem"))
                .andExpect(jsonPath("$.components.schemas.Problem.required", containsInAnyOrder("status", "title")))
                .andExpect(jsonPath("$.components.schemas.FieldError.properties.code.enum", containsInAnyOrder(
                        "required", "email", "past", "unique", "invalid", "not-found", "in-use")))
                .andExpect(jsonPath("$.components.schemas.FieldError.required", containsInAnyOrder("code", "message")))
                .andExpect(jsonPath("$.paths['/api/authors/{id}'].delete.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/books/{id}'].delete.responses['409']").doesNotExist());
    }
}
