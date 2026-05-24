# IssueFlow — Setup & Run

## Prerequisites
- Java 21
- Docker + Docker Compose
- Maven wrapper bundled (use `./mvnw` on Linux/macOS, `.\mvnw.cmd` on Windows PowerShell)

## 1. Start the database
```bash
docker compose up -d
```
> `compose.yml` does not mount a volume — database state is ephemeral.
> Wipe and restart: `docker compose down && docker compose up -d`

## 2. Build
```bash
./mvnw clean package -DskipTests
```

## 3. Run
```bash
./mvnw spring-boot:run
```
- App: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

## 4. Run tests
```bash
./mvnw test
```
Tests use an in-memory H2 database in PostgreSQL compatibility mode; Docker is not required for tests.

## 5. Stop the database
```bash
docker compose down
```

## Smoke test

After `./mvnw spring-boot:run`, in a second terminal:

```bash
# Create a user (open to anonymous; first user can be ADMIN)
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","email":"a@x.com","fullName":"Admin","role":"ADMIN","password":"password123"}'

# Login
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

# Verify the token works
curl http://localhost:8080/auth/me -H "Authorization: Bearer $TOKEN"

# Create a project
curl -X POST http://localhost:8080/projects \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Demo","description":"smoke","ownerId":1}'
```

On Windows PowerShell, replace `curl` with `curl.exe` and adjust quoting.

## Configuration

| Setting | Default | How to override |
|---|---|---|
| Server port | `8080` | `server.port` in `application.yaml` |
| DB URL | `jdbc:postgresql://localhost:5432/issueflow` | `spring.datasource.url` |
| DB user / pass | `issueflow` / `issueflow` | `spring.datasource.username` / `password` |
| JWT secret | dev fallback (≥32 bytes required) | env var `ISSUEFLOW_JWT_SECRET` (set in production) |
| JWT expiration | `3600` seconds | `app.jwt.expiration-seconds` |
| Attachment max size | `10MB` | `spring.servlet.multipart.max-file-size` and `app.attachments.max-bytes` |
| Overdue escalation cron | every 15 minutes | `app.schedule.overdue-escalation-cron` (`"-"` to disable) |
| Revoked-token purge cron | daily at 03:00 | `app.schedule.revoked-token-purge-cron` (`"-"` to disable) |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Connection refused` on startup | Postgres not up yet | `docker compose ps`; wait a few seconds and retry |
| `Schema-validation: wrong column type` | Migration drift from entity model | `docker compose down -v && docker compose up -d` to wipe and re-run V1 |
| `Migration checksum mismatch` | Edited V1 after first apply | Same fix; wipe the volume and re-run |
| `Could not resolve placeholder 'app.schedule...'` | Test profile missing schedule properties | Verify `src/test/resources/application.yaml` has the schedule block |
| Login returns 401 with valid credentials | JWT secret is shorter than 32 bytes | Set `ISSUEFLOW_JWT_SECRET` to a 32+ byte string |
| Port 8080 already in use | Another process holds it | `server.port: 8081` in `application.yaml`, or stop the other process |