# BFF
Backend-for-Frontend service that aggregates data from backend services and exposes UI-friendly APIs.

## CI/CD

GitHub Actions workflow `.github/workflows/bff-ci-cd.yml` runs on pull requests to `main` and on pushes to `main`.

The CI job:
- sets up Java 21;
- runs `mvn -B clean verify`;
- builds the Docker image `studybytes/bff:ci`.

The deploy job runs only after a push to `main`. It connects to the VPS over SSH, updates the repository, runs `docker compose up -d --build`, and checks the BFF health endpoint.

Required GitHub Secrets:
- `VPS_HOST` - VPS host name or IP address;
- `VPS_PORT` - SSH port;
- `VPS_USER` - SSH user;
- `VPS_SSH_KEY` - private SSH key for deployment;
- `VPS_DEPLOY_BASE_PATH` - base directory for deployed services;
- `BFF_HEALTHCHECK_URL` - optional, defaults to `http://127.0.0.1:8080/health`.

Required server setup:
- Docker and Docker Compose plugin are installed;
- deployment user can run Docker commands;
- shared backend network exists, by default `studybytes_backend_net`;
- `.env` exists on the server or is created from `.env.example`.

BFF environment variables:
- `SERVER_PORT` - external and application port, default `8080`;
- `SVC_USER_BASE_URL` - UserService URL, default `http://user-service:8081`;
- `SVC_COURSE_BASE_URL` - CourseService URL, default `http://course-service:8082`;
- `SVC_LEARNING_BASE_URL` - LearningService URL, default `http://learning-service:8090`;
- `BACKEND_NETWORK` - shared Docker network, default `studybytes_backend_net`.

## Site-facing API

External frontend API is exposed only under `/api/v1`.

User/account/auth endpoints:
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/register-teacher-request`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/refresh`
- `GET /api/v1/auth/csrf`
- `GET /api/v1/me`
- `GET /api/v1/me/settings`
- `PUT /api/v1/me/settings`
- `PUT /api/v1/me/profile`
- `PUT /api/v1/me/password`
- `GET /api/v1/i18n/default-locale`

Teacher request endpoints:
- `POST /api/v1/teacher-requests`
- `GET /api/v1/teacher-requests/me`
- `GET /api/v1/admin/teacher-requests`
- `POST /api/v1/admin/teacher-requests/{requestId}/approve`
- `POST /api/v1/admin/teacher-requests/{requestId}/reject`

Internal UserService paths such as `/api/v1/users/*` are not exposed by the BFF.

## Auth mode (production)

Preferred mode is BFF-managed httpOnly cookies:
- BFF reads token fields from auth responses and sets `access_token` + `refresh_token` cookies.
- Site should use `credentials: include`.
- `POST /api/v1/auth/refresh` can use refresh token from cookie when request body does not contain `refreshToken`.
- `POST /api/v1/auth/logout` clears auth cookies at BFF level.
- Token fields in JSON are still forwarded for MVP compatibility.

Cookie configuration environment variables:
- `BFF_AUTH_ACCESS_COOKIE_NAME` (default `access_token`)
- `BFF_AUTH_REFRESH_COOKIE_NAME` (default `refresh_token`)
- `BFF_AUTH_COOKIE_SECURE` (default `true`)
- `BFF_AUTH_COOKIE_SAME_SITE` (default `Lax`)
- `BFF_AUTH_COOKIE_PATH` (default `/`)
- `BFF_AUTH_COOKIE_DOMAIN` (optional)

## Error format

BFF normalizes errors to:

```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "requestId": "req-123",
  "validationErrors": [
    { "field": "fieldName", "message": "Field error message" }
  ]
}
```
