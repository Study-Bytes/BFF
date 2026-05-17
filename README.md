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
