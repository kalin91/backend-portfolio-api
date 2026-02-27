# GitHub Copilot Instructions for backend-portfolio-api

This file provides context and guidelines for AI agents working on this project. The project is a production-style demo API intended as a backend portfolio piece, demonstrating professional engineering practices.

## Project Overview

- **Type**: Netflix DGS GraphQL API (Single Endpoint)
- **Purpose**: Backend portfolio and professional demonstration.

## Technology Stack

- **Languages**: Java 21
- **Framework**: Spring Boot 3.5.11
- **Build Tool**: Gradle 8.14.3
- **Reactive Stack**: Spring WebFlux (Project Reactor)
- **GraphQL**: Netflix DGS Framework
- **Persistence**: Spring Data JPA with Hibernate
- **Database**: H2 Database (In-Memory for demo, accessible via console)
- **Security**: Spring Security (HTTP Basic Auth + `@EnableReactiveMethodSecurity`)
- **Containerization**: Docker

## Architecture Guidelines

The application must follow a strict layered architecture:

1.  **Controller Layer (GraphQL Data Fetchers)**: Handles GraphQL requests and responses.
2.  **Facade Layer (Optional)**: Orchestrates transactions and complex business flows.
3.  **Service Layer**: Contains business logic.
4.  **Repository Layer**: Handles data access using Spring Data JPA.

### Key Architectural Rules

- **Reactive Types**: Use `Mono` and `Flux` consistently throughout the application (WebFlux).
- **No Entities in API**: NEVER expose JPA Entities directly in the GraphQL schema. Always use DTOs.
- **Mapping**: Use MapStruct or explicit manual mappers to convert between Entities and DTOs.
- **Clean Code**: Follow SOLID principles.
- **Cloud-Ready**: Design for statelessness and containerization.

## Security Model

### Authentication

Every GraphQL operation requires **HTTP Basic Auth** (`@EnableWebFluxSecurity`). The following paths are public:

- `/actuator/health`, `/actuator/info` — liveness/readiness probes
- `/graphiql/**` — in-browser GraphQL IDE
- `/proxy/graphiql` — server-side proxy that injects credentials into the GraphiQL HTML (see `GraphiQLProxyController`).

### Authorization (Method-Level)

Method-level security is enforced via `@EnableReactiveMethodSecurity` and `@PreAuthorize` annotations on DGS data fetcher methods. SpEL expression constants are defined in the `Permission` enum to keep annotations DRY.

| Role          | Permitted Operations                                                                         |
| ------------- | -------------------------------------------------------------------------------------------- |
| `ROLE_READER` | All query methods: `customers`, `customer`, `orders`, `order`                                |
| `ROLE_WRITER` | `ROLE_READER` permissions + `createCustomer`, `updateCustomer`, `createOrder`, `updateOrder` |
| `ROLE_ADMIN`  | `ROLE_WRITER` permissions + `deleteCustomer`, `deleteOrder`                                  |

### Permission Bitmask (`Permission` enum)

Roles are derived from a bitmask stored in the `permissions` field of each credential entry:

- `ADMIN = 1`, `WRITER = 2`, `READER = 4` (additive)
- `admin` profile: `permissions=7` (1+2+4) → `ROLE_ADMIN, ROLE_WRITER, ROLE_READER`
- `writer` profile: `permissions=6` (2+4) → `ROLE_WRITER, ROLE_READER`
- `reader` profile: `permissions=4` → `ROLE_READER`

Use `Permission.ROLE_ADMIN`, `Permission.ROLE_WRITER`, `Permission.ROLE_READER` as values for `@PreAuthorize`.

### Credential Configuration

Credentials are loaded from the `API_CREDENTIALS_JSON` environment variable. The value must be a **Base64-encoded** string of the JSON configuration.

**JSON Shape (before encoding):**

```json
{
  "admin": { "user": "api_admin", "pass": "...", "permissions": 7 },
  "writer": { "user": "api_writer", "pass": "...", "permissions": 6 },
  "reader": { "user": "api_reader", "pass": "...", "permissions": 4 }
}
```

A local-dev fallback is defined in `application.yml` via `${API_CREDENTIALS_JSON:...}`. NEVER commit production passwords. In production, supply `API_CREDENTIALS_JSON` as an environment secret (Base64 encoded).

### Addinng New GraphQL Operations

- **Queries** must have `@PreAuthorize(Permission.ROLE_READER)`.
- **Create/Update mutations** must have `@PreAuthorize(Permission.ROLE_WRITER)`.
- **Delete mutations** must have `@PreAuthorize(Permission.ROLE_ADMIN)`.

## Functional Requirements

- **Domains**: Implement sample domains including **Orders** and **Customers**.
- **CRUD**: Implement basic Create, Read, Update, Delete operations.
- **Schema**: Maintain a well-defined `src/main/resources/schema/schema.graphqls` file.
- **Seed Data**: Use startup seeding to load sample data from `src/main/resources/data/*.json` into H2 (implemented via `DataSeeder` as a `SmartInitializingSingleton`).
- **Pagination & Filtering**: Implement basic pagination (Connection/Cursor or Offset based) and filtering for list queries.
- **Validation**: Include strict input validation (JSR-303/Jakarta Validation).
- **Error Handling**: Implement global exception handling to return meaningful GraphQL errors (DataFetchingExceptionHandler).
- **Observability**:
  - **Logging**: Use SLF4J-based application logging.
  - **Health**: Expose Spring Actuator health endpoints.

## Configuration & Coding Standards

- **Configuration**: Use `application.yml`. Ensure environment-specific settings are clear.
- **Package Structure**:
  - `com.demo.portfolio.api` (Root)
  - `.domain` (Entities)
  - `.dto` (Data Transfer Objects)
  - `.fetcher` (GraphQL Data Fetchers)
  - `.service`
  - `.repository`
  - `.mapper`
  - `.config`
  - `.exception`
- **Documentation**:
  - Add full comprehensive **JavaDoc** for all main classes and every methods that include a detailed description of parameters, return values, and exceptions.
  - JavaDoc should be clear, concise, and informative, providing enough context for other developers to understand the purpose and usage of the code without needing to read the implementation details. It must not contain `@author`, `@since`, or `@version` tags.
  - Ensure code is self-documenting where possible.
  - During Code-Review, verify that all new and modified code includes appropriate JavaDoc and adheres to the project's documentation standards.

## Testing Strategy

### Unit Tests (JUnit 5 + Mockito)

- **Framework**: JUnit 5 (Jupiter) with Mockito for mocking. Always annotate test classes with `@ExtendWith(MockitoExtension.class)`.
- **Scope**: Test one class in isolation. Mock all collaborators with `@Mock`. Inject them via constructor or directly.
- **Naming Convention**: Method names should describe the behaviour being tested, e.g. `customersMapsPage()` or `adminUserHasAllRoles()`.
- **Assertions**: Use JUnit 5's `org.junit.jupiter.api.Assertions.*` (e.g. `assertEquals`, `assertTrue`, `assertNotNull`, `assertSame`). Use `assertThrows` for exception cases.
- **Reactive**: For `Mono`/`Flux` in unit tests, block with `.block()` — this is acceptable in test context because no reactive pipeline is running.
- **Security tests**: Instantiate `SecurityConfig` directly (no Spring context needed). Pass a `BCryptPasswordEncoder` and an `ObjectMapper` manually. Build `SecurityProperties` programmatically.
- **Coverage**: Every public method of every new or modified class must have at least one test. Edge cases (nulls, empty collections, boundary values) must also be covered.
- **Command**: `./gradlew test`
- **Execution Detail**: In current Gradle configuration, `test` depends on `karateTest`; running `./gradlew test` executes Karate first and then unit tests.
-  **Prioritize mocking over creating test-only methods**: At design time, prefer mocking collaborators rather than adding methods solely for testing purposes.

### Integration Tests (Karate)

- **Framework**: Karate DSL 1.5.2 (`io.karatelabs:karate-junit5`), run via `ITKarateRunner` with `@SpringBootTest(webEnvironment = RANDOM_PORT)`.
- **Feature files**: Located alongside the Java source in `src/test/java/.../fetcher/*.feature` (domain data fetchers) and `src/test/java/.../controller/*.feature` (REST controllers). Each file covers one DGS data fetcher or REST controller.
- **Authentication**: Every scenario must set the `Authorization` header explicitly:
  ```gherkin
  Given header Authorization = authHeader('admin')
  ```
  Use `authHeader('reader')` or `authHeader('writer')` for role-specific tests. The `authHeader(role)` function is defined in `karate-config.js` and reads credentials from `API_CREDENTIALS_JSON` (falls back to local-dev defaults).
- **Access-Denied Scenarios**: Each feature file must include at least two negative authorization scenarios:
  - A `reader` principal attempting a create/update mutation — expect HTTP 200 with `response.errors != null`, `response.errors[0].message == 'Forbidden'`, and the mutation field absent (`#notpresent`).
  - A `writer` principal attempting a delete mutation — expect HTTP 200 with `response.errors != null`, `response.errors[0].message == 'Forbidden'`, and the mutation field absent (`#notpresent`).
- **Assertions**: Use `match`, `match each`, and `#regex` matchers. Prefer structural matchers (`#string`, `#number`, `#notnull`, `#notpresent`) over hardcoded values to keep tests stable across seed data changes.
- **karate-config.js**: Defines `baseUrl`, `basePath`, and the `authHeader(role)` helper. Uses `JSON.parse()` (not `karate.fromJson`) and `new java.lang.String(combined).getBytes(UTF_8)` for Base64 encoding.
- **Command**: `./gradlew karateTest`

#### `ITKarateRunner.java`

**Location**: `src/test/java/com/demo/portfolio/api/ITKarateRunner.java`

The JUnit 5 bridge that starts the Spring Boot application context and then hands execution to the Karate parallel runner. Key peculiarities:

- Annotated with `@SpringBootTest(webEnvironment = RANDOM_PORT)` — the server binds to an OS-assigned ephemeral port on every test run.
- Injects `@LocalServerPort` and passes the actual port to Karate via the system property `demo.server.port`, which `karate-config.js` reads to build `baseUrl`.
- Uses `Runner.path("classpath:")` so Karate discovers **all** `*.feature` files under the test classpath automatically — no manual file registration needed.
- Parallel degree is set to `5`; adding new feature files does not require any runner change.
- The single `@Test` method asserts `results.getFailCount() == 0`, so any Karate scenario failure fails the JUnit test immediately with a descriptive error message.

#### `karate-config.js`

**Location**: `src/test/java/karate-config.js` (placed directly under the test source root so Karate finds it on the classpath)

Global configuration function executed once before any feature file. Key peculiarities:

- **Port resolution**: reads the `demo.server.port` system property set by `ITKarateRunner`; falls back to `8080` for local manual runs.
- **Credential loading**: reads `API_CREDENTIALS_JSON` from the OS environment (throws if absent), Base64-decodes it using `java.util.Base64.getDecoder()`, and parses the resulting JSON with `JSON.parse()`. **Do not** use `karate.fromJson` here — it does not handle nested Java byte-array output correctly.
- **`authHeader(role)` helper**: closes over the parsed credential map; builds a `Basic <base64>` string for the role key (`admin`, `writer`, `reader`). The Base64 encoding uses `new java.lang.String(combined).getBytes(java.nio.charset.StandardCharsets.UTF_8)` to stay in Java-interop land, avoiding character-encoding edge cases.
- **`basePath`**: set to `/model` (the DGS GraphQL endpoint path). All feature files that exercise GraphQL operations reference this via `* path basePath`.
- Timeouts: `connectTimeout` and `readTimeout` are set to `5000 ms`. Adjust if running against a slow remote instance.
- Pretty-print flags (`logPrettyRequest`, `logPrettyResponse`) are enabled to aid debugging; disable in CI if log verbosity is a concern.

#### Feature Files

**Locations**:
- `src/test/java/com/demo/portfolio/api/fetcher/customerDataFetcher.feature` — Customer CRUD operations and access-control enforcement.
- `src/test/java/com/demo/portfolio/api/fetcher/orderDataFetcher.feature` — Order CRUD operations and access-control enforcement.
- `src/test/java/com/demo/portfolio/api/controller/graphiqlProxyController.feature` — End-to-end proxy tests (see below).

General conventions for all feature files:

- **GraphQL payloads** are stored as companion `*.graphql` files co-located with the feature file and loaded with `read('FileName.graphql')`. For feature files in sub-packages other than `fetcher/`, use the `classpath:` prefix: `read('classpath:com/demo/portfolio/api/fetcher/GetCustomers.graphql')`.
- The `Background:` block sets `* url baseUrl` and (for GraphQL features) `* path basePath` so all scenarios share the same base.
- Structural Karate matchers (`#string`, `#number`, `#notnull`, `#notpresent`) are preferred over literal values to keep assertions stable when seed data changes.
- Naming convention: `camelCase` matching the class/controller under test (e.g. `customerDataFetcher.feature`, `graphiqlProxyController.feature`).

#### `graphiqlProxyController.feature` (Proxy End-to-End Tests)

**Location**: `src/test/java/com/demo/portfolio/api/controller/graphiqlProxyController.feature`

Validates the full `/proxy/graphiql` → GraphQL round-trip without embedding credentials in any URL.

#### Proxy Controller Bigger Picture (`GraphiQLProxyController`)

**Location**: `src/main/java/com/demo/portfolio/api/controller/GraphiQLProxyController.java`

This controller is a server-side bootstrap endpoint for GraphiQL sessions with role-specific credentials.
It exists to improve DX in demos/tests while keeping credential handling centralized on the backend.

**Endpoint contract**:
- `GET /proxy/graphiql?role=<admin|writer|reader>`
- Returns `text/html` (GraphiQL page) with an injected `<script>` that patches `window.fetch`.
- The patch adds `Authorization: Basic <base64(user:pass)>` only when the outgoing URL targets the GraphQL path (`dgs.graphql.path`, default `/model`).

**End-to-end request lifecycle**:
1. Browser calls `/proxy/graphiql?role=...`.
2. Controller loads credential set from `SecurityProperties` (decoded `API_CREDENTIALS_JSON`) and validates the role.
3. Controller computes Basic token from the selected credential profile.
4. Controller fetches the public GraphiQL HTML (`/graphiql`) server-side via `WebClient`.
5. Controller injects a script before `</head>` (or appends at end if missing).
6. Browser renders modified GraphiQL; subsequent GraphQL requests automatically include `Authorization` header.
7. GraphQL endpoint (`/model`) still enforces method-level authorization (`@PreAuthorize` + `Permission`).

**Security model clarifications**:
- The proxy does **not** bypass Spring Security or method-level checks.
- Proxy role choice only determines which credential profile is injected.
- Forbidden operations still return GraphQL errors (`message = 'Forbidden'`) with HTTP 200, as expected by DGS/Karate assertions.
- Unknown role returns HTTP 400 from the proxy itself.
- Upstream GraphiQL fetch failures are mapped to HTTP 502 (Bad Gateway).

**Operational notes for agents**:
- Any change to `dgs.graphql.path` must remain aligned with proxy script matching logic.
- If proxy tests start returning 502, investigate first whether server-side fetch to `/graphiql` is resolving the correct host/port in the active runtime.
- Keep proxy logic isolated from GraphQL business code; do not move auth logic into schema resolvers.

**Flow per scenario**:
1. `GET /proxy/graphiql?role=<role>` — server fetches the public `/graphiql` HTML, injects a `<script>` that contains the `Authorization` header value, and returns the modified HTML.
2. A JavaScript helper (`extractAuth`) extracts `'Authorization': 'Basic ...'` from the HTML using a regex.
3. The extracted header is used for a `POST /model` GraphQL call — exactly as the browser-side GraphiQL UI would behave.

**Scenarios**:
- `admin` queries the customer list (4 success + 2 forbidden scenarios total — see file).
- `reader` queries a single customer.
- `reader` queries orders filtered by status.
- `writer` creates an order.
- `reader` credentials via proxy **cannot** create a customer → `Forbidden`.
- `writer` credentials via proxy **cannot** delete a customer → `Forbidden`.
- Unknown role → HTTP 400.

**Why this is different from other feature files**: the proxy feature does **two** HTTP calls per scenario (GET then POST). The `url` remains pinned to `baseUrl`; only the `path` is changed between calls. No `path basePath` is set in the `Background` — each scenario sets its paths explicitly.

### Full Suite

```bash
./gradlew verifyAllTests   # runs test + karateTest, fails if either has failures
./gradlew check            # same + generates merged HTML report
```

### Running `karateTest` and `test` Tasks (Env Requirements)

Both tasks rely on credentials being available at runtime.

- `./gradlew karateTest`
  - Runs Karate integration tests (`ITKarateRunner` + all `*.feature`).
  - Requires `API_CREDENTIALS_JSON` (Base64-encoded JSON) to be set in the shell environment.
  - If missing/invalid, `karate-config.js` fails early by design.

- `./gradlew test`
  - In this project, `test` depends on `karateTest`, then runs unit tests.
  - Therefore it has the **same** `API_CREDENTIALS_JSON` requirement as `karateTest`.

**Local execution pattern**:

```bash
export API_CREDENTIALS_JSON='<base64-json>'
./gradlew karateTest
./gradlew test
```

**VS Code task note**:
- The workspace includes a helper task (`Test with Credentials`) that writes credentials into `.vscode/test_env.txt`.
- Agents/users must still ensure `API_CREDENTIALS_JSON` is exported into the process environment used by Gradle.

## Deliverables

- **Dockerfile**: Minimal image for running the application using Eclipse Temurin 21 (Alpine). Supports CDS (Class Data Sharing) for faster startup.
- **README.md**: Comprehensive instructions on building, running, and testing the application.
- **CI/CD**: GitHub Actions workflows for PR validation (`pr-validation.yml`) and deployment to Cloud Run (`deploy.yml`).

## Quality Statement

The project should demonstrate **clean architecture**, **cloud-ready design**, and **professional backend engineering practices**.

## Implementation Notes for Agents

- **Interceptors Pipeline**:
  - `SanitizingInterceptor` runs on request path and validates/sanitizes incoming GraphQL payloads before execution.
  - `ErrorEnhancerInterceptor` runs on response path and enhances enum coercion errors via `GraphQLErrorEnhancerService`.
- **Query Hardening**:
  - `QuerySanitizerService` enforces max query length (`10_000`), max depth (`10`), rejects dangerous patterns, and validates syntax by parsing GraphQL AST.
  - `GraphQLInstrumentationConfig` enforces max query complexity (`850`).
- **Reactive + Blocking Bridge**:
  - Services wrap blocking JPA operations with `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- **Scheduler Placement Rule**:
  - Apply `subscribeOn(Schedulers.boundedElastic())` only at the boundary where blocking work is created (service/data-loader), not in data fetchers.
  - Avoid double scheduling (`subscribeOn` in both fetcher and service) unless a special case is documented.
- **Reactive Error Logging Rule**:
  - Prefer centralized error mapping/logging in `GlobalDataFetcherExceptionHandler` and interceptors.
  - Service/data-loader boundaries may add contextual `doOnError` logs (warn for expected not-found paths, error for unexpected failures), but avoid repetitive per-step logging in fetchers.
- **Testing**:
  - Every time an agent calls a sub-agent he must specify to them that they're sub-agents and that they can skip running the full suite on every change, but the root agent must ensure that the final state of the codebase always passes all tests before any commit.
  - Every time a root agent finish implementing new changes, they must run the tests related to the affected classes.
  - If test fails because port 8080 is in use and the test requires it, the root agent can use `lsof -i :8080` to find the process and `kill <pid>` to free the port, then re-run tests.
  - If any of the executed tests fails, the root agent must investigate and fix the issue before proceeding. Then validates again and repets until the previous tests pass successfully. Only then the root agent can commit the changes.
  - sub-agents could skip running the related tests on every change, but the root agent must ensure that the final state of the codebase always passes all the related tests before any commit.
