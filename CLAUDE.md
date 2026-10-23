# CLAUDE.md

Spring Boot REST API consumed by a CRUD web UI built from the `crud-ui-blueprint` template. The UI is generated from
this API's OpenAPI contract, so the contract rules in `docs/API-CONTRACT.md` are requirements, not suggestions.

## Project
The placeholders below (`<project>`, `<groupId>`, `<realm>`, `<Resource>`) stand for these values everywhere in this
file.
- Name: `library-api`: the repository directory and the Maven `artifactId`.
- Maven `groupId` and base Java package: `pl.kartofel.library`.
- Keycloak realm: `library`; realm roles `library-viewer` (read) and `library-editor` (read and write); clients
  `library-ui` (the web UI) and `library-api` (service account for smoke tests).
- Resources (OpenAPI tag, path, relations):
  - `Author` (tag `Authors`, `/api/authors`); label `"firstName lastName"`; referenced by `Book`.
  - `Book` (tag `Books`, `/api/books`); required relation `authorId` → `Author` (an author has many books).

## Working rules
- Use plan mode for any task with 3+ steps or an architectural decision; write the spec before the code.
- After any correction from the user, add a rule to `tasks/lessons.md`. Review it at session start.
- Simplicity first: the smallest change that meets the requirement; find root causes, no temporary fixes.
- Never mark a task done without the Definition of Done below.
- Use the `spring-boot-template` plugin's skills (`spring-boot-template:spring-boot`, `:testing-patterns`,
  `:conventions`) and agents (`:spring-boot-engineer`, `:code-reviewer`, `:devops-engineer`).
- Write all code, comments, commit messages and docs in English. The user may write in Polish; reply in the user's
  language, but never put Polish into files.

## Stack and project rules
- Java 21, the latest Spring Boot 4.x, Maven. No Lombok; constructor injection.
- PostgreSQL and Keycloak run with Docker Compose (`docker-compose.yml`); Kubernetes manifests in `k8s/`.
- Maven `artifactId` = the repository directory name; `groupId` and base package from the Project section.
- Semantic versioning from `1.0.0`: PATCH by default, MINOR for a backward-compatible feature, MAJOR for a breaking
  API change. Every contract change bumps the version.
- Positive and negative tests for all generated code. CircleCI pipeline in `.circleci/config.yml`.
- Minimize the amount of code. Update `README.md` with every version (changelog, API, errors, "Connect a UI").

## API contract (consumed by the Web UI)
- The contract rules are in `docs/API-CONTRACT.md` (a copy from the UI template; do not edit it here). This section
  is how this API follows them.
- Controllers never expose entities: use `*Response`, `*CreateRequest`, `*UpdateRequest` records.
- Errors are RFC 9457 Problem Details; validation and unique-constraint errors add `errors: [{field, code, message}]`
  (one `ApiExceptionHandler`). `code` is a stable, documented enum the UI translates (`required`, `email`, `past`,
  `unique`, `invalid`, `date-range`, `not-found`, `in-use`, plus your own); `message` is an English fallback.
  `field` is omitted for errors about the whole record (`in-use`). A new constraint or code must be added to the
  mapping, the `FieldError` schema enum, the README table and the tests.
- Cross-field rules are class-level constraints that report the error on one field, so the UI can show it next to an
  input (e.g. `@DateRange` reports the later date with code `date-range`).
- Error responses are documented by convention in one `OpenApiCustomizer` (400/409 on POST/PUT, 400 on list GETs,
  404 on `{id}` paths, 409 on DELETE of a referenced resource, `application/problem+json`, schema `Problem`), not by
  `@ApiResponse` on each method. Keep new endpoints consistent with it; add a resource to the referenced paths when
  another resource starts referencing it.
- List endpoints are paged (`page`, `size`, `sort`, `q`) and return `PagedModel` (`{content, page}`).
- List filters are optional, named query parameters next to `q`, `page`, `size` and `sort`, never a generic filter
  expression. Bind them to a `<Resource>Filter` record (`@ParameterObject @Valid`, `@Parameter(description)` per
  component) that builds a JPA `Specification`; filters combine with AND, and an invalid value returns 400 with
  `errors` (field = parameter name). `q` escapes the LIKE wildcards `%`, `_` and `\`.
  Example: `GET /api/orders?q=&status=&customerId=`.
- Relations:
  - Requests reference a related record by id: `<relation>Id` (e.g. `customerId`), nullable. An unknown id returns 400
    with a field error on `<relation>Id`, code `not-found`.
  - Responses embed a `Reference` summary `{id, label}` (e.g. `customer`), where `label` is the related record's
    display name, so a list never needs one request per row. Load the relation with `@EntityGraph` on the
    repository's `findAll(Specification, Pageable)` and `findById` (`open-in-view` is off). Write endpoints of a
    resource with a relation are `@Transactional` and map the response inside the transaction; `PUT` uses
    `saveAndFlush`, so `version` and `updatedAt` in the response are current. Without the transaction, `save` merges
    the detached entity and the relation becomes an uninitialized proxy (`LazyInitializationException`, 500). Test
    changing a relation from one record to another, not only setting and clearing it, in an integration test: a
    mocked `@WebMvcTest` cannot see JPA proxies.
  - Deleting a record that is still referenced returns 409 with a `detail` and `errors: [{code: "in-use", message}]`
    without `field`; it never cascades. The foreign key is `ON DELETE RESTRICT` as a backstop.
  - A filter on a relation is `<relation>Id`.
- Method names are the OpenAPI operationIds: `list<Resource>s`, `get<Resource>`, `create<Resource>`,
  `update<Resource>`, `delete<Resource>`.
- Endpoints live under `/api`; reads need role `<realm>-viewer` or `<realm>-editor`, writes need `<realm>-editor`.
- `PUT` is a full replacement: `*UpdateRequest` has the same constraints as `*CreateRequest` plus a required `version`
  from the last read (409 on mismatch). Optional fields sent as null are cleared; a field with a default sent as null
  gets its default again: the entity setter maps null to it (`Objects.requireNonNullElse`), the request documents it
  with `@Schema(defaultValue)`, and the migration has the same `DEFAULT`.
- Server-set fields (`createdAt`, `updatedAt` via `@CreationTimestamp`/`@UpdateTimestamp`, `Instant`) appear only in
  responses, marked `@Schema(accessMode = READ_ONLY)`.
- Validation constraints must translate to JSON Schema/JavaScript (the UI generates Zod schemas from the contract): no
  Java-only regex syntax such as `(?s)` or possessive quantifiers; prefer `@NotBlank`, `@Size`, `@Email`. A rule Bean
  Validation cannot express gets a custom constraint plus the matching `@Schema` keyword (e.g. a step:
  `@MultipleOf("0.5")` with `@Schema(multipleOf = 0.5)`), and a DB `CHECK` with the same rule.
- Response records mark always-present fields with `@Schema(requiredMode = REQUIRED)`, so the generated TypeScript
  types are not optional; responses are documented as `application/json` (`springdoc.default-produces-media-type`).
- Unexpected exceptions become a 500 Problem Detail with a generic `detail` (logged, never the exception message).
- The OpenAPI `info.version` comes from Maven `build-info`; never hard-code it.
- The contract is exported to `contract/openapi.json` by an integration test (`exportOpenApiContract`), which writes the
  file first and asserts afterwards, so a failing assertion still leaves the file to inspect. After any API change run
  `mvn clean verify` and commit the regenerated file (CI fails otherwise).

## Database
- Flyway owns the schema (`src/main/resources/db/migration`); Hibernate only validates it (`ddl-auto: validate`), so
  any mismatch between entities and migrations fails at startup.
- Never edit a migration that has been applied; every change is one new file `V<n>__<description>.sql`.
- Every entity change ships with its migration in the same commit.
- A new NOT NULL column gets a `DEFAULT` or a backfill, so existing rows keep working.
- Name constraints and indexes explicitly (`pk_`, `uk_`, `ck_`, `fk_`, `ix_`); index every foreign key column; map
  each unique constraint name to the `unique` error code in the exception handler.

## Keycloak
The UI logs in against this realm, so its shape is fixed:
- One realm file, `k8s/realm.json`, imported with `--import-realm` by Docker Compose (Keycloak `start-dev`) and by
  Kubernetes (a `configMapGenerator`, so a changed file rolls a new pod).
- Realm roles `<realm>-viewer` and `<realm>-editor`, read from the `realm_access.roles` claim and mapped to
  `ROLE_<role>` in `SecurityConfig` (resource server, `jwk-set-uri` from `JWK_SET_URI`).
- Client `<realm>-ui`: public, standard flow only, PKCE `S256`, redirect URI `http://localhost:5173/*`, web origin
  `http://localhost:5173`.
- Client `<realm>-api`: confidential, service account with `<realm>-editor`, secret `<realm>-api-secret` (dev only),
  for `client_credentials` smoke tests.
- Demo users (password = username): `viewer` with `<realm>-viewer`, `editor` with `<realm>-editor`. The UI's e2e tests
  log in as them.
- Internationalization on: `en` (default) and `pl`; the login page follows `ui_locales` from the UI.
- `--import-realm` skips an existing realm: after changing `realm.json` run
  `docker compose up -d --force-recreate keycloak`.
- Ports: Keycloak 8080, API 8081 (`SERVER_PORT`), PostgreSQL 5432. They may be taken by another project's containers;
  check `podman ps` (or `docker ps`) first, and stop them only when the user asks, with `stop` (not `compose down`),
  so their data survives.

## Implementation notes (Spring Boot 4 / Spring Data 4 / springdoc 3)
- `PropertyReferenceException` and `TypeInformation` moved to `org.springframework.data.core` in Spring Data 4.
- Do not put `@EnableSpringDataWebSupport` on the application class: `@WebMvcTest` registers it twice and fails.
  Controllers return `PagedModel` explicitly instead.
- Annotate `Pageable` parameters with springdoc's `@ParameterObject`, otherwise the contract shows one `pageable`
  object instead of `page`/`size`/`sort`.
- Any `@ApiResponse` on a controller method (also via a meta-annotation) stops springdoc 3 from generating the success
  response; that is why error responses are added by an `OpenApiCustomizer`.
- Schemas registered manually must use `ModelConverters.getInstance(true)` (OpenAPI 3.1); the default instance omits
  `"type": "object"`.
- Error codes come from `FieldError#getCode()` (the constraint's simple name, e.g. `NotBlank`) mapped in
  `ApiExceptionHandler#code`. The `Problem` record exists only to document the error body; keep it in sync with what
  the handler returns.
- The catch-all handler has no `@ResponseStatus`, so springdoc does not add a 500 to the contract.
- `not-found` and `in-use` are thrown as Spring's `ErrorResponseException` with a prepared `ProblemDetail`;
  `ResponseEntityExceptionHandler` renders it, so no extra `@ExceptionHandler` is needed.
- `@WebMvcTest(XController.class)` creates no repositories: a repository newly injected into an existing controller
  needs a `@MockitoBean` in that controller's test.
- `@NotBlank` appears in the contract only as `minLength: 1`, so a whitespace-only value passes client-side
  validation and is rejected by the server with a field error.
- `@Size(max = N)` next to `@NotBlank` overwrites that `minLength: 1` with `0` (and `@Schema(minLength = 1)` does not
  win), so required text fields use `@NotBlank @Size(min = 1, max = N)`. An empty value then breaks both constraints;
  `ApiExceptionHandler` keeps only `required` for a field that has it.
- Integration tests use Testcontainers. With Podman set `DOCKER_HOST` to the Podman socket and
  `TESTCONTAINERS_RYUK_DISABLED=true` (see README). Without Docker/Podman they are skipped, and the contract is not
  regenerated.

## Workflow
- Build with `mvn clean verify`, not plain `mvn verify`: classes compiled by the IDE in `target` can hide `javac`
  errors.
- macOS ships BSD tools: `sed` has no `\|` alternation and `grep` has no `-P`; use `perl -pi -e` for regex edits, and
  the Edit tool for multi-line changes.
- Fix a bug test-first: write the failing test, run it to see the reported error, then fix.
- Smoke-test the local API with a `client_credentials` token from `<realm>-api` (`curl` commands in README, "Run
  locally"), and delete the smoke data afterwards.
- After a version bump, rebuild and restart the locally running API (`nohup java -jar target/<project>-<version>.jar &`,
  after `pkill -f <project>-`), and recreate Keycloak if `k8s/realm.json` changed.
- Commit only when asked, and push only when asked; "commit" alone does not mean push. Before committing, scan the
  changed files for Polish text.
- After a push, check CI: `gh api repos/<owner>/<repo>/commits/<sha>/status` (context `ci/circleci: build`; logs at the
  `target_url`). Poll every 30 s or slower. Take `<owner>/<repo>` from `git remote get-url origin` instead of typing
  it: a misspelled owner gives a 404 that looks like an access problem.

## README "Connect a UI"
The README keeps a section with the values the UI's `crud-ui-init` asks for, as `.env.local` lines:
```
VITE_OIDC_AUTHORITY=http://localhost:8080/realms/<realm>
VITE_OIDC_CLIENT_ID=<realm>-ui
VITE_OIDC_ROLES_CLAIM=realm_access.roles
API_PROXY_TARGET=http://localhost:8081
CONTRACT_SOURCE=../<project>/contract/openapi.json
E2E_WRITER_USER=editor
E2E_WRITER_PASSWORD=editor
E2E_READER_USER=viewer
E2E_READER_PASSWORD=viewer
```
plus the read role `<realm>-viewer` and the write role `<realm>-editor`.

## Definition of Done
1. `mvn clean verify` passes, with integration tests running (not skipped).
2. `contract/openapi.json` is regenerated, its `info.version` bumped, and committed with the change.
3. The change has positive and negative tests; a relation change has an integration test.
4. The local API was smoke-tested with `curl` and the smoke data deleted.
5. README updated: changelog, API and error tables, "Connect a UI".
