# Backend Portfolio API

This is a production-style demo API intended as a backend portfolio piece, demonstrating professional engineering practices.

## Technology Stack

- **Java 21**
- **Spring Boot 3.5.11**
- **Gradle 8.14.3**
- **Spring WebFlux** (Reactive Stack)
- **Netflix DGS Framework** (GraphQL)
- **Spring Data JPA** with Hibernate
- **H2 Database** (In-Memory)
- **Spring Security** (HTTP Basic Auth + method-level authorization)
- **MapStruct** for DTO mapping
- **Docker** for containerization

## Architecture

The application follows a strict layered architecture:

1. **Controller Layer (GraphQL Data Fetchers)**: Handles GraphQL requests and responses.
2. **Service Layer**: Contains business logic and transaction management.
3. **Repository Layer**: Handles data access using Spring Data JPA.

Reactive types (`Mono` and `Flux`) are used consistently in the API layer, with blocking JPA calls wrapped appropriately using `Schedulers.boundedElastic()`.

## Features

- **GraphQL API**: Schema-first design with a single endpoint (`/model`).
- **Reactive Stack**: Built on Spring WebFlux, bridging blocking JPA repositories with reactive data fetchers.
- **N+1 Optimization**: Solved using `DataLoaders` with composite keys for argument-aware batching.
- **Security**: HTTP Basic Auth + role-based access control on every GraphQL operation. Query complexity analysis guards against resource-exhaustion attacks.
- **Pagination**: Standard offset-based pagination strategy.
- **Error Handling**: Global exception handling producing standard GraphQL errors with extended metadata.
- **Interceptors Pipeline**: Request sanitization before execution plus response-time enhancement of enum validation errors.
- **Observability**: SLF4J-based application logging and Actuator health endpoints.
- **Tools**: H2 Console accessible at `http://localhost:8082`.

## Security

### Overview

Every GraphQL operation is protected by **HTTP Basic Auth**. The API enforces role-based authorization at the method level using Spring Security's `@PreAuthorize` annotation on DGS data fetcher methods. This means authorization is checked *after* authentication, giving precise per-operation control.

CSRF protection is intentionally disabled in `SecurityConfig` because the API is stateless and does not use session cookies.

### Role Profiles

Three role profiles are available. Roles are cumulative: `admin` holds all three roles.

- `reader` → `ROLE_READER` → read-only queries (`customers`, `customer`, `orders`, `order`)
- `writer` → `ROLE_WRITER`, `ROLE_READER` → all reads + `createCustomer`, `updateCustomer`, `createOrder`, `updateOrder`
- `admin` → `ROLE_ADMIN`, `ROLE_WRITER`, `ROLE_READER` → all reads + all writes + `deleteCustomer`, `deleteOrder`

Public paths (no authentication required):

- `GET /actuator/health` and `GET /actuator/info` — liveness/readiness probes
- `GET /graphiql/**` — in-browser GraphQL IDE (development convenience)
- `GET /proxy/graphiql?role=<role>` — server-side proxy that injects credentials into the GraphiQL HTML; no username/password appears in any browser-visible URL

### Credential Configuration

Credentials are loaded from the `API_CREDENTIALS_JSON` environment variable at startup. The variable must be a **Base64-encoded** string of a JSON object with three keys (`admin`, `writer`, `reader`), each carrying a `user`/`pass`/`permissions` triple:

**Raw JSON (before encoding):**
```json
{
  "admin":  { "user": "api_admin",  "pass": "<strong-secret>", "permissions": 7 },
  "writer": { "user": "api_writer", "pass": "<strong-secret>", "permissions": 6 },
  "reader": { "user": "api_reader", "pass": "<strong-secret>", "permissions": 4 }
}
```

The `permissions` field is an additive bitmask: `ADMIN=1`, `WRITER=2`, `READER=4`. A value of `7` (= 1+2+4) grants all three roles; `6` (= 2+4) grants writer + reader; `4` grants reader only.

If `API_CREDENTIALS_JSON` is not set, the application falls back to local-dev defaults defined in `application.yml`. **Never use the defaults in production.**

#### Example: starting with custom credentials

1. Create the JSON string.
2. Encode it to Base64 (e.g., `echo -n '{"admin":...}' | base64`).
3. Set the environment variable.

```bash
# Example (Base64 for default local credentials)
export API_CREDENTIALS_JSON='eyJhbWluIjp7InVzZXIiOiJhcGlfYWRtaW4iLCJwYXNzIjoiYWRtaW4xMjMiLCJwZXJ...'
./gradlew bootRun
```

### Security Headers

- **CSRF**: Disabled (stateless API).
- **X-Frame-Options**: Disabled to allow embedding the GraphiQL interface in iframes (e.g., for portfolio demos).

#### Example: calling the API with Basic Auth

```bash
# Read-only query as reader
curl -u api_reader:reader123 -X POST http://localhost:8080/model \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ customers(page:0,size:5) { content { id firstName } } }"}'

# Create a customer as writer
curl -u api_writer:writer123 -X POST http://localhost:8080/model \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation { createCustomer(input:{firstName:\"Jane\",lastName:\"Doe\",email:\"jane@example.com\"}) { id } }"}'

# Delete a customer as admin
curl -u api_admin:admin123 -X POST http://localhost:8080/model \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation { deleteCustomer(id:\"1\") }"}'
```

### Permission Bitmask (`Permission` enum)

The `Permission` enum in `com.demo.portfolio.api.config` encodes each permission level as a single bit. This allows a credential JSON entry to declare its roles via a compact integer field rather than a list of strings. The `SecurityConfig` converts each entry's bitmask into Spring `GrantedAuthority` values at startup using `Permission.fromMask(int)`.

```text
ADMIN  = 1  → ROLE_ADMIN  (deleteCustomer, deleteOrder)
WRITER = 2  → ROLE_WRITER (createCustomer, updateCustomer, createOrder, updateOrder)
READER = 4  → ROLE_READER (customers, customer, orders, order)
```

The enum also defines SpEL expression constants (`ROLE_ADMIN`, `ROLE_WRITER`, `ROLE_READER`) that are used directly as `@PreAuthorize` annotation values, keeping the authorization logic DRY and refactoring-safe.

## Technical Highlights & Patterns

This project implements several advanced patterns to demonstrate professional backend engineering:

### 1. N+1 Problem Solver (Composite Key DataLoaders)

Instead of simple ID-based batching, this API uses a **Composite Record Key** (`OrdersByCustomerKey`) for DataLoaders. This allows batching child collections (Orders) while preserving arguments passed to the field (e.g., filtering orders by status *within* a batched call).

- *See:* `OrdersByCustomerDataLoader.java`

### 2. Query Complexity Instrumentation

To protect the server from resource exhaustion, the API implements a custom `Instrumentation` that calculates the "cost" of a query before execution.

- **Root Fields**: High cost.
- **Nested Lists**: Multiplied cost.
- **Limit**: Queries exceeding the budget (850 points) are rejected immediately.
- *See:* `GraphQLInstrumentationConfig.java`

### 3. Reactive-Blocking Bridge

The application runs on Netty (WebFlux) but uses Hibernate (Blocking). It bridges these worlds by wrapping blocking repository calls in `Mono.fromCallable` and subscribing on `Schedulers.boundedElastic()`, ensuring the event loop remains non-blocking.

- Scheduler placement rule: scheduling is applied at the blocking boundary (service/data-loader), not in GraphQL data fetchers.
- Service/data-loader boundaries add contextual reactive error logs (`doOnError`) while keeping global GraphQL error shaping in the centralized handler.

### 4. Request Sanitization (Pre-Execution Validation)

Before any query reaches the execution engine, a `WebGraphQlInterceptor` validates and sanitizes the raw request payload. This adds a security layer against injection or malformed input at the gateway level.

- Enforced guards in `QuerySanitizerService`: max length (`10_000` chars), max depth (`10`), dangerous pattern detection, and GraphQL syntax parsing.

- *See:* `SanitizingInterceptor.java`

### 5. Response Error Enhancement

Some GraphQL validation errors (like invalid enum values) are generated before data fetchers execute. The API post-processes those errors to return clearer messages with valid enum values.

- *See:* `ErrorEnhancerInterceptor.java`, `GraphQLErrorEnhancerService.java`

### 6. Global Error Handling

Exceptions are not just thrown; they are intercepted and transformed into meaningful GraphQL errors.

- **TypedGraphQLError**: Returns specific error codes (e.g., `NOT_FOUND`, `BAD_REQUEST`, `INTERNAL_ERROR`) and HTTP status suggestions in the `extensions` map.
- *See:* `GlobalDataFetcherExceptionHandler.java`

### 7. Type-Safe Mapping

Uses **MapStruct** for compile-time generation of mappers between JPA Entities and GraphQL DTOs, avoiding runtime reflection overhead.

## Getting Started

### Prerequisites

- Java 21
- Docker (optional)

### Building the Project

```bash
./gradlew build
```

### Running the Application

```bash
./gradlew bootRun
```

The application will start on port `8080`.
The GraphQL endpoint is available at `http://localhost:8080/model`.
GraphiQL (UI for testing queries) is available at `http://localhost:8080/graphiql`.

On startup, when the database is empty, sample data is seeded from JSON resources (`src/main/resources/data/customers.json` and `src/main/resources/data/orders.json`) by `DataSeeder`.

> **Note:** When running locally without setting `API_CREDENTIALS_JSON`, the application uses the built-in development defaults (`api_admin` / `admin123`, `api_writer` / `writer123`, `api_reader` / `reader123`). GraphiQL requires these credentials too — use the **HTTP Headers** pane to set `Authorization: Basic YXBpX2FkbWluOmFkbWluMTIz` (base64 of `api_admin:admin123`), or open the **proxy endpoint** at `http://localhost:8080/proxy/graphiql?role=admin` which injects the credentials automatically.

### Running Tests

The project employs a comprehensive testing strategy combining unit and integration tests.

#### **1. Unit Testing (JUnit 5 & Mockito)**

- **Scope**: Internal business logic, service layer, and configuration classes.
- **Command**: `./gradlew test`

> **Important:** In the current Gradle configuration, `test` depends on `karateTest`, so `./gradlew test` executes Karate integration tests first and then unit tests.

#### **2. Integration Testing (Karate)**

- **Scope**: End-to-end API testing — GraphQL queries, mutations, H2 database, schema validation, access-control enforcement, and the GraphiQL proxy round-trip.
- **Features**: Parallel execution (`degree=5`), full request/response logging, schema-first validation.
- **Auth**: Each scenario sends an explicit `Authorization: Basic …` header via the `authHeader('role')` helper defined in `karate-config.js`.
- **Command**: `./gradlew karateTest`

> **Integration tests with custom credentials**: If you set `API_CREDENTIALS_JSON` for the app, export the same variable before running Karate so `karate-config.js` picks it up and generates the correct headers.

##### Key Files

| File | Purpose |
|---|---|
| `src/test/java/com/demo/portfolio/api/ITKarateRunner.java` | JUnit 5 runner that boots the Spring context on a random port and delegates to `Runner.path("classpath:")` for auto-discovery of all `*.feature` files. Passes the actual port via the `demo.server.port` system property. |
| `src/test/java/karate-config.js` | Global config loaded once before every feature. Resolves `baseUrl` from `demo.server.port`, Base64-decodes `API_CREDENTIALS_JSON`, and exposes `authHeader(role)` and `basePath=/model` to all scenarios. |
| `src/test/java/.../fetcher/customerDataFetcher.feature` | Customer CRUD (get, list, create, update, delete) + 2 access-control scenarios (reader blocked from create, writer blocked from delete). |
| `src/test/java/.../fetcher/orderDataFetcher.feature` | Order CRUD (get, filter by status & customer, create, update, delete) + 2 access-control scenarios. |
| `src/test/java/.../controller/graphiqlProxyController.feature` | End-to-end proxy tests: GET proxy → extract injected `Authorization` from HTML → POST GraphQL. 4 success scenarios + 2 forbidden scenarios + unknown-role → 400. |
| `src/test/java/.../fetcher/*.graphql` | Named GraphQL operation strings loaded by feature files via `read()`. |

##### GraphiQL Proxy Feature

The proxy feature file exercises the full browser-free flow:
1. `GET /proxy/graphiql?role=<role>` — fetches the (public) GraphiQL HTML server-side and injects a `window.fetch` patch that embeds the `Authorization` header.
2. A JavaScript helper extracts the injected header from the HTML.
3. A `POST /model` GraphQL request is made with the extracted header.

This confirms both that the proxy renders correctly **and** that the credentials it injects are accepted (or denied) by the GraphQL layer according to the role's permissions.

#### **3. Full Suite & Reporting**

Run both suites and generate a combined HTML report:

```bash
./gradlew check
```

Or run just the merged verification/report phase explicitly:

```bash
./gradlew verifyAllTests
```

- **Report Location**: `build/reports/tests/merged/index.html`

### Docker

To build and run the application using Docker:

```bash
docker build -t backend-portfolio-api .
docker run -p 8080:8080 -p 8082:8082 \
  -e SPRING_PROFILES_ACTIVE=local \
  -e API_CREDENTIALS_JSON='{"admin":{"user":"api_admin","pass":"s3cr3t","permissions":7},"writer":{"user":"api_writer","pass":"wr1t3","permissions":6},"reader":{"user":"api_reader","pass":"r3@d","permissions":4}}' \
  backend-portfolio-api
```

`8080` serves the GraphQL API (`/model`). `8082` exposes the H2 web console started by `H2ConsoleConfig`.

## CI/CD Pipeline

The project uses GitHub Actions for Continuous Integration and Deployment.

### 1. PR Validation (`.github/workflows/pr-validation.yml`)
Triggers on Pull Requests to `main`.
- **Build**: Compiles the code using Gradle.
- **Tests**: Runs both Unit and Integration (Karate) tests.
- **Checks**: Ensures the build passes before merging.

### 2. Deployment (`.github/workflows/deploy.yml`)
Triggers on Release publication (or manual dispatch).
- **Build & Test**: Runs the full build and test suite.
- **Docker Build**: Builds the container image.
- **Smoke Test**: Runs the container locally and verifies health endpoints (`/actuator/health`).
- **Push**: Pushes the image to Google Artifact Registry.
- **Deploy**: Deploys the container to **Google Cloud Run** (North America South 1).


## Sample Queries

### Get Customers with Pagination

```graphql
query GetCustomers($page: Int, $size: Int, $orderStatus: OrderStatus, $orderPage: Int, $orderSize: Int) {
  customers(page: $page, size: $size) {
    content {
      id
      firstName
      lastName
      email
      orders(status: $orderStatus, page: $orderPage, size: $orderSize) {
        id
        status
        totalAmount
      }
    }
    totalPages
    totalElements
    number
  }
}
```

```json
// variables
{
    "page": 0,
    "size": 100,
    "orderStatus": null,
    "orderPage": 0,
    "orderSize": 100
}
```

### Create an Order

```graphql
mutation {
  createOrder(input: {
    customerId: "1",
    totalAmount: 199.99
  }) {
    id
    status
    totalAmount
  }
}
```
