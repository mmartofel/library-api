# Library API

A Spring Boot 4 REST API for managing `Author` and `Book` records (an author has many books), stored in PostgreSQL.
Every endpoint requires an OAuth2 JWT issued by Keycloak. The OpenAPI contract in `contract/openapi.json` drives a CRUD
web UI generated from the `crud-ui-blueprint` template.

Version: **1.0.0**

## Changelog
- **1.0.0**: First version.
  - `Author` at `/api/authors` and `Book` at `/api/books`, with paged, searchable and filterable lists.
  - A book belongs to exactly one author: requests send `authorId` (required), responses embed `author: {id, label}`.
  - Deleting an author who still has books returns 409 with code `in-use`.
  - Errors are Problem Details with stable field error codes.
  - Keycloak realm `library` with roles `library-viewer` and `library-editor`.

## Stack
- Java 21, Spring Boot 4.1.1, Maven
- Spring Data JPA + PostgreSQL 18, Flyway migrations
- Spring Security OAuth2 Resource Server (JWT, validated against Keycloak's JWK set)
- Keycloak 26.8.1, realm `library`: confidential client `library-api`, public client `library-ui` (Authorization Code + PKCE)
- springdoc-openapi 3.1.1 (OpenAPI contract + Swagger UI)
- Jib + Skaffold + Kustomize for Kubernetes
- JUnit 5, MockMvc, Testcontainers; CircleCI

## API
All `/api/**` endpoints require `Authorization: Bearer <token>`. Reads need realm role `library-viewer` or
`library-editor`; writes need `library-editor`. Public: `/actuator/health/**`, `/v3/api-docs`, `/swagger-ui.html`.

Lists take `page` (from 0), `size` (default 20, max 100), `sort` (e.g. `sort=lastName,desc`; an unknown property
returns 400) and the filters below, combined with AND. `q` is a case-insensitive "contains" search; `%`, `_` and `\`
match literally.

`PUT` is a full replacement: the body has the same fields as `POST` plus the `version` from the last read. Optional
fields sent as `null` or left out are cleared. If someone changed the record in the meantime, it returns 409.

### Authors

| Method | Path                | Result                                                       |
|--------|---------------------|--------------------------------------------------------------|
| GET    | `/api/authors`      | 200, page `{content, page:{size, number, totalElements, totalPages}}`; 400 on an invalid sort |
| GET    | `/api/authors/{id}` | 200, or 404                                                  |
| POST   | `/api/authors`      | 201, 400 on an invalid body, 409 if the email is taken       |
| PUT    | `/api/authors/{id}` | 200, 400, 404, or 409 (email taken or stale `version`)       |
| DELETE | `/api/authors/{id}` | 204, 404, or 409 if the author still has books (`in-use`)    |

Filter: `q` searches first name, last name and email.

| Field | Type and rules |
|-------|----------------|
| `id`, `version` | Read-only (`version` is required in `PUT`) |
| `firstName`, `lastName` | Required, not blank, max 100 |
| `birthDate` | Optional ISO date in the past |
| `email` | Optional, well-formed, max 255, unique; an empty string is stored as no email |
| `createdAt`, `updatedAt` | Read-only timestamps set by the server (ISO 8601, UTC) |

An author who still has books cannot be deleted. The response is 409, the `detail` says how many books, and `errors` is
`[{"code":"in-use","message":"still referenced by books"}]` (no `field`). Reassign or delete the books first.

### Books

| Method | Path              | Result                                                         |
|--------|-------------------|----------------------------------------------------------------|
| GET    | `/api/books`      | 200, page `{content, page}`; 400 on an invalid filter or sort  |
| GET    | `/api/books/{id}` | 200, or 404                                                    |
| POST   | `/api/books`      | 201, 400 (invalid body or unknown `authorId`), 409 if the ISBN is taken |
| PUT    | `/api/books/{id}` | 200, 400, 404, or 409 (ISBN taken or stale `version`)          |
| DELETE | `/api/books/{id}` | 204, or 404                                                    |

Filters: `q` searches title and ISBN; `authorId` returns the books of one author.

| Field | Type and rules |
|-------|----------------|
| `id`, `version` | Read-only (`version` is required in `PUT`) |
| `title` | Required, not blank, max 200 |
| `isbn` | Optional, 10 to 17 characters (ISBN-10 or ISBN-13, hyphens allowed), unique |
| `publishedOn` | Optional ISO date in the past |
| `pages` | Optional integer, at least 1 |
| `authorId` (request) | Required id of an author; an unknown id returns 400 `not-found` on `authorId` |
| `author` (response) | `{id, label}`, where `label` is "firstName lastName"; always present |
| `createdAt`, `updatedAt` | Read-only timestamps set by the server (ISO 8601, UTC) |

### Errors

Errors are [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`, schema
`Problem` in the contract). Validation and uniqueness errors list the affected fields in `errors`:
```json
{"status":400,"title":"Bad Request","detail":"Invalid request content.","instance":"/api/books",
 "errors":[{"field":"authorId","code":"required","message":"must not be null"}]}
```
`code` is stable, so the UI should translate it. `message` is an English fallback.

| `code`      | Meaning                                                    | Source                         |
|-------------|------------------------------------------------------------|--------------------------------|
| `required`  | The field is missing or blank                              | `@NotBlank`, `@NotNull`        |
| `email`     | Not a well-formed email address                            | `@Email`                       |
| `past`      | The date must be in the past                               | `@Past`                        |
| `unique`    | The value is already used by another record (409): author `email`, book `isbn` | unique constraints `uk_author_email`, `uk_book_isbn` |
| `not-found` | The referenced record does not exist: `authorId` (400)     | lookup of the author           |
| `in-use`    | The record is still referenced and cannot be deleted (409, no `field`) | delete of an author with books |
| `invalid`   | Any other constraint: too long, ISBN length, `pages` below 1, malformed filter value | `@Size`, `@Min`, type conversion |

Which error responses each endpoint can return:
- 400 on `POST` and `PUT`, and on the list endpoints (invalid filter or sort).
- 404 on every `/{id}` path.
- 409 on `POST` and `PUT`: a duplicate email or ISBN has `errors`, a stale `version` has none.
- 409 on `DELETE /api/authors/{id}` while the author has books (`in-use`).
- 500 on an unexpected server error, as Problem Details with a generic `detail` (the cause is only logged). It is not
  listed in the contract.

### OpenAPI contract
The contract for the Web UI is committed in `contract/openapi.json`. `mvn clean verify` regenerates it (in
`LibraryApplicationIT`), and CI fails if the committed file is out of date. While the app runs, Swagger UI is at
http://localhost:8081/swagger-ui.html: click **Authorize** and paste a token.

## Connect a UI
Values for the UI's `crud-ui-init`, as `.env.local` lines:
```
VITE_OIDC_AUTHORITY=http://localhost:8080/realms/library
VITE_OIDC_CLIENT_ID=library-ui
VITE_OIDC_ROLES_CLAIM=realm_access.roles
API_PROXY_TARGET=http://localhost:8081
CONTRACT_SOURCE=../library-api/contract/openapi.json
E2E_WRITER_USER=editor
E2E_WRITER_PASSWORD=editor
E2E_READER_USER=viewer
E2E_READER_PASSWORD=viewer
```
Read role: `library-viewer`. Write role: `library-editor`.

## Run locally
```shell
docker compose up -d          # PostgreSQL on 5432, Keycloak on 8080 (or: podman compose up -d)
mvn spring-boot:run           # API on 8081

TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=library-api -d client_secret=library-api-secret \
  http://localhost:8080/realms/library/protocol/openid-connect/token | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"firstName":"Stanislaw","lastName":"Lem","birthDate":"1921-09-12"}' \
  http://localhost:8081/api/authors
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Solaris","isbn":"978-0156027601","publishedOn":"1961-06-01","pages":204,"authorId":1}' \
  http://localhost:8081/api/books
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8081/api/books?q=sol&authorId=1&sort=title"
```
The `library-api` service account has the `library-editor` role.

For the browser UI, the realm also has a public client, `library-ui`:
- Flow: Authorization Code + PKCE.
- Redirect URI and web origin: `http://localhost:5173`.
- Demo users (username/password): `viewer`/`viewer` with role `library-viewer`, `editor`/`editor` with role
  `library-editor`.

The login page follows the UI language: pass `ui_locales=pl` (or `en`, the default) in the authorization request.

### Applying changes to the Keycloak realm
Keycloak imports `k8s/realm.json` with `--import-realm`, which **skips a realm that already exists**:
- **Docker Compose:** run `docker compose up -d --force-recreate keycloak`. Users and settings added in the Admin
  Console are lost.
- **Kubernetes / Skaffold:** Kustomize's `configMapGenerator` adds a content hash to the ConfigMap name, so a changed
  `realm.json` rolls out a new Keycloak pod, which imports the realm again.

### Database migrations
Flyway creates and upgrades the schema at startup from `src/main/resources/db/migration` (`V<n>__<description>.sql`).
Hibernate runs with `ddl-auto: validate`, so the app does not start if the entities and the migrations disagree.

## Deploy to Kubernetes
```shell
skaffold dev                                  # local cluster, e.g. minikube or kind
skaffold run --default-repo=<your-registry>   # remote cluster
```
Jib builds the image without a Docker daemon. The manifests in `k8s/` (PostgreSQL, Keycloak, the app) go through
Kustomize. Skaffold forwards the API to `localhost:8081` and Keycloak to `localhost:8080`, so the curl commands above
work unchanged. The client secret, demo user passwords and DB credentials are demo values; replace them for any real
environment.

## Tests
```shell
mvn test                                      # unit tests (MockMvc)
mvn clean verify                              # plus Testcontainers integration tests (needs Docker or Podman)
mvn test -Dtest=BookControllerTest#createBookUnknownAuthor   # single test
```
With Podman instead of Docker, point Testcontainers at the Podman socket:
```shell
DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}') \
TESTCONTAINERS_RYUK_DISABLED=true mvn clean verify
```
The integration tests are skipped automatically when Docker is not available. CircleCI (`.circleci/config.yml`) runs
`mvn clean verify` on a machine executor with Docker, then checks that `contract/openapi.json` is committed.
