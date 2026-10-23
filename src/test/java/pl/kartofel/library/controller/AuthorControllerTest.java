package pl.kartofel.library.controller;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import pl.kartofel.library.repository.AuthorRepository;
import pl.kartofel.library.repository.BookRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

@WebMvcTest(AuthorController.class)
@Import(SecurityConfig.class)
class AuthorControllerTest {

    private static final String URL = "/api/authors";
    private static final String VALID = """
            {"firstName":"Olga","lastName":"Tokarczuk","birthDate":"1962-01-29","email":"olga@example.com"}""";
    private static final String UPDATE = """
            {"firstName":"Olga","lastName":"Tokarczuk","version":0}""";

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AuthorRepository repository;
    @MockitoBean
    BookRepository bookRepository;

    private static JwtRequestPostProcessor editor() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + SecurityConfig.EDITOR));
    }

    private static JwtRequestPostProcessor viewer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + SecurityConfig.VIEWER));
    }

    private static Author author(Long id) {
        Author a = new Author();
        a.setId(id);
        a.setFirstName("Olga");
        a.setLastName("Tokarczuk");
        ReflectionTestUtils.setField(a, "version", 0L);
        return a;
    }

    private void saveReturnsArgument() {
        when(repository.save(any())).thenAnswer(inv -> {
            Author a = inv.getArgument(0);
            a.setId(1L);
            ReflectionTestUtils.setField(a, "version", 0L);
            return a;
        });
    }

    private void stored(Author a) {
        when(repository.findById(a.getId())).thenReturn(Optional.of(a));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void postInvalid(String body, String field, String code) throws Exception {
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == '%s')].code".formatted(field), contains(code)));
        verify(repository, never()).save(any());
    }

    @Test
    void listAuthors() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(author(1L)), PageRequest.of(0, 20), 1));
        mvc.perform(get(URL).param("q", "tok").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].lastName").value("Tokarczuk"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void listAuthorsBadSort() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenThrow(new PropertyReferenceException("foo", TypeInformation.of(Author.class), List.of()));
        mvc.perform(get(URL).param("sort", "foo").with(viewer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAuthor() throws Exception {
        when(repository.findById(1L)).thenReturn(Optional.of(author(1L)));
        mvc.perform(get(URL + "/1").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Olga"))
                .andExpect(jsonPath("$.email").isEmpty())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void getAuthorNotFound() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(get(URL + "/99").with(viewer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Author 99 not found"));
    }

    @Test
    void createAuthor() throws Exception {
        saveReturnsArgument();
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.birthDate").value("1962-01-29"))
                .andExpect(jsonPath("$.email").value("olga@example.com"));
    }

    @Test
    void createAuthorBlankEmailIsNone() throws Exception {
        saveReturnsArgument();
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"Olga","lastName":"Tokarczuk","email":""}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").isEmpty());
    }

    @Test
    void createAuthorMissingNames() throws Exception {
        postInvalid("{}", "firstName", "required");
        postInvalid("""
                {"firstName":"Olga","lastName":"  "}""", "lastName", "required");
    }

    @Test
    void createAuthorTooLongName() throws Exception {
        postInvalid("""
                {"firstName":"%s","lastName":"Tokarczuk"}""".formatted("a".repeat(101)), "firstName", "invalid");
    }

    @Test
    void createAuthorNameAtMaxLength() throws Exception {
        saveReturnsArgument();
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"%s","lastName":"%s"}""".formatted("a".repeat(100), "b".repeat(100))))
                .andExpect(status().isCreated());
    }

    @Test
    void createAuthorInvalidEmail() throws Exception {
        postInvalid("""
                {"firstName":"Olga","lastName":"Tokarczuk","email":"not-an-email"}""", "email", "email");
    }

    @Test
    void createAuthorFutureBirthDate() throws Exception {
        postInvalid("""
                {"firstName":"Olga","lastName":"Tokarczuk","birthDate":"%s"}""".formatted(LocalDate.now().plusDays(1)),
                "birthDate", "past");
    }

    @Test
    void createAuthorDuplicateEmail() throws Exception {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", new SQLException(), Author.UNIQUE_EMAIL)));
        mvc.perform(post(URL).with(editor()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The email is already in use"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("unique"));
    }

    @Test
    void updateAuthorReplacesRecord() throws Exception {
        Author stored = author(1L);
        stored.setBirthDate(LocalDate.of(1962, 1, 29));
        stored.setEmail("olga@example.com");
        stored(stored);
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"Joseph","lastName":"Conrad","birthDate":null,"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Joseph"))
                .andExpect(jsonPath("$.lastName").value("Conrad"))
                .andExpect(jsonPath("$.birthDate").isEmpty())
                .andExpect(jsonPath("$.email").isEmpty());
    }

    @Test
    void updateAuthorInvalid() throws Exception {
        stored(author(1L));
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"","lastName":"Tokarczuk","email":"bad","version":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'firstName')].code", contains("required")))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].code", contains("email")));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateAuthorWithoutVersion() throws Exception {
        stored(author(1L));
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateAuthorStaleVersion() throws Exception {
        stored(author(1L));
        mvc.perform(put(URL + "/1").with(editor()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"firstName":"Olga","lastName":"Tokarczuk","version":3}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateAuthorNotFound() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(put(URL + "/99").with(editor()).contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isNotFound());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void deleteAuthor() throws Exception {
        when(repository.existsById(1L)).thenReturn(true);
        mvc.perform(delete(URL + "/1").with(editor())).andExpect(status().isNoContent());
        verify(repository).deleteById(1L);
    }

    @Test
    void deleteAuthorWithBooksIsInUse() throws Exception {
        when(repository.existsById(1L)).thenReturn(true);
        when(bookRepository.countByAuthorId(1L)).thenReturn(2L);
        mvc.perform(delete(URL + "/1").with(editor()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Author 1 has 2 books; reassign or delete them first"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("in-use"))
                .andExpect(jsonPath("$.errors[0].message").value("still referenced by books"))
                .andExpect(jsonPath("$.errors[0].field").doesNotExist());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void deleteAuthorNotFound() throws Exception {
        when(repository.existsById(99L)).thenReturn(false);
        mvc.perform(delete(URL + "/99").with(editor())).andExpect(status().isNotFound());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void unexpectedErrorIsProblemDetail() throws Exception {
        when(repository.findById(1L)).thenThrow(new IllegalStateException("internal secret"));
        mvc.perform(get(URL + "/1").with(viewer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Unexpected server error"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void viewerCannotModify() throws Exception {
        mvc.perform(post(URL).with(viewer()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        mvc.perform(put(URL + "/1").with(viewer()).contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isForbidden());
        mvc.perform(delete(URL + "/1").with(viewer())).andExpect(status().isForbidden());
        verify(repository, never()).save(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void noRoleIsForbidden() throws Exception {
        mvc.perform(get(URL).with(jwt())).andExpect(status().isForbidden());
    }

    @Test
    void unauthorizedWithoutToken() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        verify(repository, never()).save(any());
    }
}
