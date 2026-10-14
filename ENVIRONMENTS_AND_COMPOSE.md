# Environments, Compose files, and the release path

This guide separates three different jobs that were easy to mix up:

1. Spring profiles choose backend behavior at runtime.
2. Maven profiles choose build and test behavior.
3. Docker Compose starts the supporting services and selects which backend image to run.

## Spring profiles: runtime behavior

The backend has `dev`, `test`, and `prod` Spring profiles:

| Profile | Configuration | Used for |
| --- | --- | --- |
| `dev` | `src/main/resources/application-dev.yml` | Local development logging and Swagger. |
| `test` | `src/main/resources/application-test.yml` | Automated tests using test-only settings. |
| `prod` | `src/main/resources/application-prod.yml` | Production logging and disabled Swagger. Credentials and service addresses still come from environment variables. |

`application.yml` is shared configuration. Run locally with `SPRING_PROFILES_ACTIVE=dev`; the checked-in Compose demo does this for its source-built backend. The release overlay sets `SPRING_PROFILES_ACTIVE=prod` while running the same tested JAR inside the release image. Environment-specific addresses and secrets are not compiled into the JAR.

## Maven profiles: build and test behavior

Maven has a `ci` profile. It opts the test run into SMTP, Kafka, and Redis integration checks. It does not select the running application's environment and does not produce a different dev or prod JAR.

```powershell
.\mvnw.cmd clean test        # regular local tests; optional external-service tests stay skipped
.\mvnw.cmd -Pci clean verify # CI suite; start compose.ci.yml first
```

GitHub Actions uses the second command after starting isolated Kafka and Mailpit services from `compose.ci.yml`.

## What each Compose file does

Compose files are instructions for Docker on the machine where the command runs. Pushing them to GitHub stores the instructions; it does not start them on that machine or deploy them to Oracle by itself.

| File | Purpose |
| --- | --- |
| `docker-compose.yml` | Full local development infrastructure for running the backend from an IDE: database, Redis, Elasticsearch, Keycloak, Kafka, Mailpit, gateway, and observability. |
| `docker-compose.demo.yml` | Backend demo services built from the current local source. Frontends, Kafka UI and Mailpit are outside this stack; Kafka is added by the Hostinger mail overlay. Elasticsearch (`search`) is an opt-in profile, off by default. Grafana, Prometheus, Loki and Alloy run by default. |
| `compose.domain.yml` | Host-based API/Auth/Grafana gateway settings and an optional `public` Cloudflare Tunnel service. Marketing and static frontends use Vercel/Pages. |
| `compose.mail-hostinger.yml` | Hostinger SMTP settings plus the Kafka broker required by the durable booking-email pipeline. Mailpit remains in local dev/CI only. |
| `compose.release-demo.yml` | Replaces only the demo backend with a tested GHCR image digest and selects Spring `prod`. It is an overlay, not a standalone stack. |
| `compose.ci.yml` | Disposable Kafka and Mailpit for GitHub Actions tests. CI stops these services after the job. |

Use the single menu to choose the two supported local workflows:

| Entry point | Result |
| --- | --- |
| `START-CHARGEOPS.bat` | Opens the local workflow menu. No VPS or Dokploy instance is needed. |
| `launchers/dev/start.bat` | Starts the lighter dev dependencies, Spring Boot, Vite and Expo Web locally. |
| `launchers/demo/start.bat` | Builds backend demo services, starts Hostinger mail and the Cloudflare Tunnel. Marketing, Console, and Driver Web are deployed separately. |
| `launchers/dev/stop.bat`, `launchers/demo/stop.bat` | Stop their Docker services without removing volumes. |

Previous domain-specific steps, the CI image smoke launcher, Dokploy deploy workflow/helper and Tailscale launchers are retained in `launchers/archive/`; they are not the normal entry points.

The demo launcher includes Grafana, Prometheus, Loki and Alloy by default. The `search` profile starts Elasticsearch only when station text-search ranking is specifically needed. Set `ELASTICSEARCH_ENABLED=true` and `ELASTICSEARCH_REINDEX_ON_STARTUP=true` in `.env.demo`, then start the demo with `--profile public --profile search` to enable it.

Use the scripts as the normal entry points instead of manually guessing overlay order. `Dockerfile` builds from source for local use. `Dockerfile.release` packages the JAR produced by CI and does not compile a second copy on the deployment host.

## What happens after pushing to GitHub

The current workflow in `.github/workflows/backend-release.yml` has separate gates:

1. A pull request or push to `main` runs tests, creates an SBOM and quality report, and stores the tested JAR as an artifact. It does not deploy the app.
2. A manual workflow run on `main` can publish the tested image to GHCR for `amd64` and `arm64`. The release job blocks publication if the configured high/critical vulnerability scan fails.
3. The workflow stops after test and optional GHCR image publication. It does not deploy to a VPS.

The prior Dokploy deploy job and its helper are backed up in `launchers/archive/`. The immutable-image smoke launcher is also retained there for later CI/CD work; neither is needed for the local-first workflow.

## Server deployment is deferred

The active development/demo workflow is local: run CI on GitHub and use `START-CHARGEOPS.bat` for dev or demo validation. VPS provisioning and Dokploy deployment are removed from the active workflow; their previous config remains in the archive.
