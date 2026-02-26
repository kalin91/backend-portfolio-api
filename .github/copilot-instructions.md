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

### Integration Tests (Karate)

- **Framework**: Karate DSL 1.5.2 (`io.karatelabs:karate-junit5`), run via `ITKarateRunner` with `@SpringBootTest(webEnvironment = RANDOM_PORT)`.
- **Feature files**: Located alongside the Java source in `src/test/java/.../fetcher/*.feature`. Each file covers one DGS data fetcher.
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

### Full Suite

```bash
./gradlew verifyAllTests   # runs test + karateTest, fails if either has failures
./gradlew check            # same + generates merged HTML report
```

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
