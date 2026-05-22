# IssueFlow — Setup & Run

## Prerequisites
- Java 21
- Docker + Docker Compose
- Maven wrapper bundled (use `./mvnw` on Linux/macOS, `.\mvnw.cmd` on Windows PowerShell)

## 1. Start the database
docker compose up -d
> Note: `compose.yml` does not mount a volume — database state is ephemeral.
> To wipe and restart: `docker compose down && docker compose up -d`

## 2. Build
./mvnw clean package -DskipTests

## 3. Run
./mvnw spring-boot:run
- App: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

## 4. Run tests
./mvnw test
Tests use an in-memory H2 database in PostgreSQL compatibility mode; Docker is not required for tests.

## 5. Stop the database
docker compose down

## Configuration
| Setting | Default | How to override |
|---|---|---|
| Server port | 8080 | `server.port` in `application.yaml` |
| DB URL | `jdbc:postgresql://localhost:5432/issueflow` | `spring.datasource.url` |
| DB user / pass | `issueflow` / `issueflow` | `spring.datasource.username` / `password` |
| JWT secret | dev fallback in `application.yaml` | env var `ISSUEFLOW_JWT_SECRET` (required in production) |
| JWT expiration | 3600 seconds | `app.jwt.expiration-seconds` |