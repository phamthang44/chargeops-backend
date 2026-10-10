# ChargeOps — Backend Core Engine

> **Enterprise EV Charging Infrastructure, Spatial Booking & Operations Platform for Vietnam**  
> *BSc (Hons) Computing Final Year Project*

ChargeOps Backend is an enterprise-grade backend system powering an end-to-end electric vehicle (EV) charging network across Vietnam. Engineered with **Java 21** and **Spring Boot 3.4+ / Spring Modulith**, it coordinates high-throughput spatial discovery, transactional time-slot booking holds, multi-party refund accounting, real-time hardware telemetry, AI-driven policy retrieval (RAG), event-driven notifications, and centralized user governance.

---

## 🌐 Public Domain & Authentication Invariant

The entire platform operates under a single canonical HTTPS public domain enabled by Tailscale Funnel:

```text
https://thang.tail704409.ts.net
```

> [!IMPORTANT]
> **Production & Integration Contract**:
> 1. **Single Public Origin**: All client applications (Web Portal, Driver Mobile, Keycloak Auth & Realm, Google OAuth Broker Callback, Backend API, WebSocket, Grafana) interact via `https://thang.tail704409.ts.net`.
> 2. **Prohibition of Port 8088 in Client Contexts**: Port `8088` (`127.0.0.1:8088`) is strictly an internal local port on the host machine where Nginx Gateway listens for reverse-proxy mapping forwarded from Tailscale Funnel (`443 -> 8088`). External clients, OAuth redirect URIs, and frontend configs must **NEVER** reference port 8088.
> 3. **Google OAuth Contract**: The registered Authorized Redirect URI is:
>    ```text
>    https://thang.tail704409.ts.net/realms/chargeops/broker/google/endpoint
>    ```
>    Any callback referencing `8088` violates Google OAuth contracts and causes `redirect_uri_mismatch`. Keycloak is pinned with `KC_HOSTNAME: https://thang.tail704409.ts.net`.

---

## 🏛️ System Architecture

ChargeOps follows a clean modular monolith architecture with event-driven integration patterns:

```mermaid
graph TD
    Client[Web & Mobile Clients] -->|HTTPS 443| Funnel[Tailscale Funnel :443]
    Funnel --> Gateway[Nginx Gateway :8088 internal]
    Gateway --> API[Spring Boot 21 Backend :8081]
    Gateway --> Keycloak[Keycloak IAM :8080]

    subgraph Persistence & Infrastructure
        API --> PostGIS[(PostgreSQL 16 + PostGIS 3.4)]
        API --> Redis[(Redis 7 - Locks & Leases)]
        API --> ES[(Elasticsearch 9.4.2 - Spatial Search)]
        API --> Kafka[Apache Kafka 4.1.2 - Outbox Events]
        API --> Mail[Mailpit SMTP :1025]
        API --> Dify[Dify AI Engine - Policy RAG]
    end

    subgraph Observability
        API --> Actuator[Micrometer Actuator]
        Actuator --> Prometheus[Prometheus :9090]
        API --> Alloy[Grafana Alloy] --> Loki[Loki] --> Grafana[Grafana :3000]
    end
```

### Core Technology Stack

| Component | Technology | Role & Implementation Details |
|---|---|---|
| **Core Framework** | Java 21, Spring Boot 3.4+, Spring Modulith | Domain-driven modular architecture, transactional boundary control |
| **Relational Database** | PostgreSQL 16 + PostGIS 3.4 | Spatial queries (`ST_DWithin`, `ST_DistanceSphere`), spatial indexing (GIST) |
| **Distributed Cache & Locking** | Redis 7 | 10-minute booking slot holds, distributed lock coordination |
| **Search Engine** | Elasticsearch 9.4.2 | Real-time station discovery, multi-criteria filtering (connectors, power, pricing) |
| **Event Broker & Messaging** | Apache Kafka 4.1.2 (KRaft) | Transactional Outbox pattern, durable event delivery, notification decoupling |
| **Identity & Access Management** | Keycloak 26.0.8 (OAuth2 Resource Server) | Role-Based Access Control (Admin, Station Owner, Station Staff, Driver) |
| **Database Migrations** | Flyway (V1 - V65+) | Versioned, zero-drift schema evolution, role standardization & audit tables |
| **AI & Knowledge Retrieval** | Dify Cloud / Self-hosted REST API | BKG-067 Policy Assistant RAG, quota management, conversation history recovery |
| **Observability & Telemetry** | Prometheus, Grafana, Loki, Alloy | Real-time health metrics, access logs, JVM heap & connection pool monitoring |
| **Testing** | JUnit 5, Testcontainers, MockMvc, AssertJ | Comprehensive integration and regression test coverage across all domain modules |

---

## ⚡ Core Domain Modules & Business Capabilities

### 1. 🛡️ User Management & Security Governance (`user` & `auth`)
- **Centralized User Directory (UM-01 → UM-05)**: REST API (`/api/v1/admin/users`) with server-side pagination, keyword search, role filtering (`ADMIN`, `OWNER`, `STAFF`, `DRIVER`), and status filtering (`ACTIVE`, `SUSPENDED`).
- **Account Suspension & Re-activation**:
  - Administrative endpoints (`/suspend` and `/activate`) requiring mandatory suspension reasons (5–500 characters).
  - Full audit logging (`admin_audit_logs`) tracking administrative actions, operator IDs, timestamps, and reason payloads.
  - Privilege safeguards preventing admins from suspending their own accounts or other Admin/Owner accounts via standard actions.
- **Actor Security Gate (`AUTH_006`)**:
  - Real-time status enforcement filter checking account state on every authenticated request.
  - Suspended accounts are immediately rejected with error code `AUTH_006` and descriptive suspension reason, instantly revoking API access.
- **Flyway Evolution (V60 - V65)**: Standardized `primary_role` column, account `status_version` for optimistic locking, and immutable audit logs.

### 2. 🧭 Spatial Station Discovery & Hardware Provisioning (`search` & `station`)
- **Spatial Discovery Engine**: High-performance spatial search matching EV driver location with compatible connectors (CCS2, Type 2, GB/T, CHAdeMO) within custom radii.
- **Hardware Topology**: Multi-level hierarchy: Station → Charge Points (AC/DC, kW capacity) → Connectors (Status: Available, Occupied, Faulted, Maintenance).
- **Driver Eligibility Engine (`isStationDriverEligible`)**: Strict business rule engine requiring both an `ACTIVE` physical station approval and a valid operator subscription license before publishing stations to driver discovery.

### 3. 📅 Booking Engine v4.9 (`booking`)
- **Time-Package Booking**: Fixed time-slot reservations eliminating queue anxiety at charging hubs.
- **10-Minute Hold Lock**: Atomically locks requested connector and time range for 10 minutes via Redis distributed locking during payment processing to prevent double-booking race conditions.
- **QR Check-in & Strict Cutoff Window**: Drivers must check in via connector QR code between `startAt` (inclusive) and `checkInDeadline` (default: `endAt - 15 minutes`). Unclaimed slots automatically transition to `CANCELLED` (`NO_SHOW`) at cutoff.
- **Hardware Session Simulation**: Virtual charging session lifecycle transitions (`CHECKED_IN` → `CHARGING` → `COMPLETED`) with telemetry metrics (kWh, power, duration).

### 4. 💰 Cancellation & Financial Boundaries (`refund` & `payment`)
- **BKG-067 Grace Window Policy**:
  - **Grace Period (10 minutes from payment confirmation)**: Drivers receive 100% full package refund upon cancellation before slot start and prior to check-in.
  - **Post-Grace / Voluntary Cancellation**: 0% refund (change of mind).
  - **No-Show Cancellation**: 0% refund.
  - **Verified Station Failure**: 100% full package refund when technical failure is substantiated by the Owner or granted by Platform Admin via dispute escalation.
- **Strict Financial Authorization Boundaries (BKG-063 / FE-20)**:
  - **Station Owner**: Exclusive access to the double-entry Owner Ledger, booking earnings, refund liabilities, and net station payout.
  - **Platform Admin**: **NO** global revenue or commercial GMV ledger; Admin financial arbitration is strictly case-by-case within escalated support tickets (`Escalated Ticket Refund Review`).
  - **Station Staff**: Limited strictly to technical equipment inspections; zero financial access.
- **Simulator Execution & Replay Safety**: Idempotent refund attempts preventing duplicate transfers or inconsistent financial states.

### 5. 🤖 AI Policy Assistant & Knowledge Hub (`assistant` & `legal`)
- **BKG-067 RAG Integration**: Integrates with Dify API to answer operational and legal questions grounded on official bilingual policy documents (VI/EN).
- **Conversation History Recovery**: Endpoints to list historical conversation sessions and retrieve prior chat turns with cursor pagination.
- **Cost & Abuse Protection**: Strict Owner-role gating, daily query quotas (e.g., 20 queries/day), countdown to quota reset, and sanitization.
- **Document Management**: Multi-locale legal documents (Terms of Service, Privacy Policy, Operational Regulations).

### 6. 📬 Notification & Real-Time Delivery (`notification` & `infra`)
- **Transactional Outbox**: Booking cancellations and status updates write to the database outbox in the same local transaction, dispatched durably via Kafka.
- **Email Delivery**: Automated confirmation and cancellation notices dispatched to Mailpit/SMTP.
- **Real-Time WebSocket/STOMP**: Private user hint queues (`/user/queue/notifications`) notifying web and mobile clients of inbox changes and refund updates without aggressive polling.

---

## 📂 Project Directory Structure

```
chargeops-backend/chargeops/
├── src/main/java/com/thang/chargeops/
│   ├── assistant/       # Dify RAG client, chat history proxy, quota evaluator
│   ├── audit/           # Administrative action audit logging (V65)
│   ├── booking/         # Time-slot bookings, hold locks, QR check-in, sessions
│   ├── incident/        # Station incidents, hardware troubleshooting
│   ├── infra/           # Kafka producer/consumer, WebSocket STOMP, mailer, Redis
│   ├── legal/           # Legal documents management, multi-lingual contracts
│   ├── location/        # Vietnamese administrative units (provinces, districts, wards)
│   ├── notification/    # Role-scoped notification inbox & preference settings
│   ├── payment/         # Simulator payment gateway, VNPay/MoMo integration contracts
│   ├── refund/          # Owner Ledger, refund dispute review, simulator attempts
│   ├── search/          # Elasticsearch geospatial indexing and query services
│   ├── station/         # Station lifecycle, charge points, connectors, license governance
│   ├── support/         # Support tickets, dispute escalation, technical evidence
│   └── user/            # Admin User Management (UM-01..UM-05), actor security gate (AUTH_006)
├── src/main/resources/
│   ├── db/migration/    # Flyway migration scripts (V1 through V65+)
│   ├── application.yml  # Base Spring Boot configuration
│   └── application-dev.yml # Local development configuration
├── docs/                # Architectural RFCs, walk-throughs, and Obsidian Knowledge Vault
├── observability/       # Prometheus, Grafana Alloy, and Loki configuration files
├── scripts/             # Demo runner scripts, acceptance datasets, SQL seeds
│   ├── demo-start.ps1   # Multi-service demo startup script (PowerShell)
│   ├── demo-stop.ps1    # Demo shutdown script
│   └── sql/
│       ├── seed-bkg-069-demo.sql  # BKG-069 benchmark acceptance dataset (ST-1901..1903)
│       └── reset-bkg-069-demo.sql # Clean reset script for repeatable test runs
├── docker-compose.yml   # Multi-container dev lab (PostGIS, Redis, Kafka, ES, Keycloak, etc.)
└── pom.xml              # Maven dependencies and build lifecycle configuration
```

---

## 🧪 BKG-069 Acceptance Dataset & Demo Runbook

The backend includes a dedicated fixture dataset and runner scripts in `scripts/` to validate the full end-to-end lifecycle:

### Test Stations
- **`ST-1901` (Active Hub - Ready for Booking)**: Healthy connectors for happy-path discovery, slot holding, and grace-period cancellations.
- **`ST-1902` (Occupied Hub - In-Use)**: Simulates active sessions and slot contention.
- **`ST-1903` (Faulted Station - Hardware Failure)**: Simulates hardware failure scenarios for dispute escalation and 100% station-fault refunds.

### Demo User Personas
| Role | Email | Password | Primary Test Flow |
|---|---|---|---|
| **Driver A** | `driver.a@chargeops.vn` | `password123` | Discovery, Booking Hold, Grace Period Cancellation, Check-in |
| **Owner A** | `owner.a@chargeops.vn` | `password123` | Multi-station ops, Owner Ledger, TOU rate cards, AI Policy Assistant |
| **Staff A** | `staff.a@chargeops.vn` | `password123` | Daily inspection checklist, incident logging on ST-1903 |
| **Platform Admin** | `admin@chargeops.vn` | `password123` | User Management (UM-01..05), Station Approval, Dispute Arbitration |

To load the acceptance fixture:
```bash
docker exec -i chargeops-postgres psql -U postgres -d chargeops < scripts/sql/seed-bkg-069-demo.sql
```

To reset the acceptance fixture:
```bash
docker exec -i chargeops-postgres psql -U postgres -d chargeops < scripts/sql/reset-bkg-069-demo.sql
```

Detailed verification steps are documented in [`docs/ChargeOps/Booking/71-bkg-069-demo-dataset-and-acceptance-runbook.vi.md`](docs/ChargeOps/Booking/71-bkg-069-demo-dataset-and-acceptance-runbook.vi.md).

---

## 🚀 Getting Started

### Prerequisites
- **Java**: JDK 21 (Eclipse Temurin or Oracle JDK)
- **Maven**: 3.9+ (or use included `./mvnw`)
- **Docker & Docker Compose**: v2.20+

---

### 1. Environment Setup

Copy the example environment files and customize as needed:
```bash
cp .env.example .env
```

Key environment configurations:
- `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`: PostgreSQL credentials
- `REDIS_HOST`, `REDIS_PORT`: Redis cache settings
- `KAFKA_BOOTSTRAP_SERVERS`: Kafka broker connection
- `ELASTICSEARCH_URL`: Elasticsearch endpoint
- `DIFY_API_KEY`, `DIFY_API_BASE_URL`: AI Assistant integration keys
- `KC_ADMIN_USER`, `KC_ADMIN_PASSWORD`: Keycloak administrator credentials

---

### 2. Start Infrastructure Containers

Launch the full supporting stack via Docker Compose:
```bash
docker compose up -d
```

Verify service health:
```bash
docker compose ps
```

| Service | Port | Description |
|---|---|---|
| **Nginx Gateway** | `8088` (internal host) | Reverse proxy mapping API, Web, and Keycloak |
| **PostgreSQL / PostGIS** | `5432` | Main database (`chargeops`) |
| **Redis** | `6379` | Cache and distributed lock manager |
| **Elasticsearch** | `9200` | Geospatial search engine |
| **Apache Kafka** | `9092` | Internal event broker |
| **Kafka UI** | `8090` | Web UI for inspecting Kafka topics and consumer groups |
| **Keycloak IAM** | `8080` | Authentication and OAuth2 token issuer |
| **Mailpit** | `8025` (UI) / `1025` (SMTP) | Local email inbox for transactional emails |
| **Grafana** | `3000` | Observability dashboards |
| **Prometheus** | `9090` | Metrics scraper |

---

### 3. Run the Spring Boot Application

Using Maven wrapper:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Or on Windows:
```cmd
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

Once started, access:
- **API Documentation (Swagger UI)**: [https://thang.tail704409.ts.net/swagger-ui/index.html](https://thang.tail704409.ts.net/swagger-ui/index.html) (or `http://localhost:8081/swagger-ui/index.html` locally)
- **OpenAPI JSON**: `http://localhost:8081/v3/api-docs`
- **Actuator Health**: `http://localhost:8081/actuator/health`

---

### 4. Running Tests

Run the full automated test suite:
```bash
./mvnw clean test
```

To run a specific test suite (e.g., Booking, User Management, or Assistant):
```bash
./mvnw test -Dtest=BookingServiceTest
./mvnw test -Dtest=UserManagementServiceTest
./mvnw test -Dtest=AssistantServiceTest
```

---

## 👨‍💻 Author

**Pham Duc Thang**  
*BSc (Hons) Computing — Final Year Project*  
ID: 001407356  
Hanoi, Vietnam
