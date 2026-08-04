# Holiday Leave Assistant

AI-powered holiday leave management application built with **Java 8 + Spring Boot 2.7** (backend) and a vanilla **JavaScript SPA** (frontend).

Ask questions about employee leave data in plain English, add or delete vacation entries through a guided chat wizard, and generate polished HTML leave reports — all grounded exclusively on your own `.xlsx` data.

---

## Table of Contents

1. [Features](#features)
2. [Architecture](#architecture)
3. [Quick Start — Local](#quick-start--local)
4. [Docker](#docker)
5. [Podman](#podman)
6. [Render.com](#rendercom)
7. [IBM Cloud Code Engine](#ibm-cloud-code-engine)
8. [Environment Variables](#environment-variables)
9. [LLM Provider Configuration](#llm-provider-configuration)
10. [API Reference](#api-reference)
11. [Key Design Notes](#key-design-notes)
12. [Troubleshooting](#troubleshooting)

---

## Features

- **Conversational leave queries** — ask anything about employee holidays; answers are grounded strictly on your Excel data (no hallucinations).
- **Add vacation wizard** — guided multi-step chat flow to book leave for any employee.
- **Delete vacation wizard** — guided multi-step chat flow to remove existing leave entries.
- **HTML report generation** — one-command generation of a full-featured leave dashboard per employee per year, including KPI cards, utilization progress bar, three Chart.js charts, and colour-coded leave-type badges.
- **Multi-file support** — upload multiple `.xlsx` planners and switch between them at runtime.
- **OpenAI-compatible LLM** — works with Ollama (local), OpenAI, Groq, OpenRouter, IBM Watsonx, or any endpoint that speaks the OpenAI chat completions API.
- **Human-readable dates** — all dates in chat and reports are displayed as `dd MMM yyyy` (e.g. `01 Aug 2026`).
- **Auto-focus input** — the chat input regains focus automatically after every agent response.
- **Docker & Podman ready** — single-command container startup with health checks.

---

## Architecture

```
holiday-leave-assistant/
├── backend/
│   └── src/main/
│       ├── java/com/holidayleave/assistant/
│       │   ├── HolidayLeaveAssistantApplication.java   ← Entry point
│       │   ├── config/
│       │   │   ├── AppProperties.java                  ← Typed env-var bindings
│       │   │   ├── SecurityConfig.java
│       │   │   └── WebConfig.java
│       │   ├── model/
│       │   │   ├── LeaveRecord.java
│       │   │   ├── VacationType.java
│       │   │   ├── LeaveAnalysisResult.java
│       │   │   ├── PendingVacation.java                ← Wizard session state
│       │   │   ├── AuditLogEntry.java
│       │   │   └── FileInfo.java
│       │   ├── analysis/
│       │   │   └── LeaveAnalysisService.java           ← Pure analytics (stateless)
│       │   ├── excel/
│       │   │   ├── PlannerExcelReader.java             ← Reads eIndkomst .xlsx format
│       │   │   └── WorkingExcelWriter.java             ← Atomic cell-level writes
│       │   ├── llm/
│       │   │   ├── LLMService.java                     ← Interface
│       │   │   └── OpenAIAdapter.java                  ← OpenAI-compatible HTTP adapter
│       │   ├── service/
│       │   │   ├── HolidayAgent.java                   ← Intent routing + LLM orchestration
│       │   │   ├── VacationCreationService.java        ← Add-vacation wizard
│       │   │   ├── VacationDeletionService.java        ← Delete-vacation wizard
│       │   │   ├── VacationTypeService.java
│       │   │   ├── AuditService.java
│       │   │   ├── SyncService.java                    ← Working → master file sync
│       │   │   ├── AppState.java                       ← Singleton app + session state
│       │   │   ├── AuthInterceptor.java
│       │   │   └── ReportGenerator.java                ← Self-contained HTML report builder
│       │   └── controller/
│       │       ├── AuthController.java
│       │       ├── ChatController.java                 ← Main chat + wizard dispatch
│       │       ├── FileController.java
│       │       ├── VacationController.java
│       │       ├── ReportsController.java
│       │       └── IndexController.java
│       └── resources/
│           ├── templates/
│           │   ├── index.html                          ← Main SPA shell (Thymeleaf)
│           │   └── login.html
│           ├── static/
│           │   ├── holiday_agent.js                    ← Frontend SPA (vanilla JS)
│           │   └── holiday_agent.css
│           └── application.properties
├── diagrams/
│   ├── use-case-diagram.drawio                         ← UML use case diagram
│   └── component-diagram.drawio                        ← UML component diagram
├── Dockerfile
├── docker-compose.yml
├── podman-compose.yml
└── .env.example
```

### Tech stack

| Layer | Technology |
|---|---|
| Language | Java 8 (compiled target); JDK 21 used inside Docker image |
| Framework | Spring Boot 2.7.18 |
| Security | Spring Security + BCrypt |
| Templates | Thymeleaf 3 |
| Excel I/O | Apache POI 5.2.5 |
| HTTP client | Spring WebFlux (WebClient) |
| Frontend | Vanilla JS (single IIFE) |
| Charts | Chart.js 4.4.3 |
| Container | Docker / Podman |

---

## Quick Start — Local

### Prerequisites

- Java 21+ (required by the `eclipse-temurin:21` builder image and recommended locally)
- Maven 3.6+
- An LLM endpoint — [Ollama](https://ollama.com) running locally is the default

### 1. Start Ollama (default LLM)

```bash
ollama serve
ollama pull llama3.2
```

Ollama must be running before the app starts. It is reached at `http://127.0.0.1:11434/v1` by default.

> **Note:** Use `127.0.0.1` rather than `localhost`. On machines with Docker Desktop installed,
> `localhost` may resolve to the Docker bridge IP (`172.17.0.2`) instead of the loopback interface,
> causing connection timeouts.

### 2. Configure environment

```bash
cp .env.example .env
# Edit .env — set LOGIN_PASSWORD_HASH at minimum
```

The `.env.example` ships with `FLASK_PORT=8080`. If you omit the `.env` file entirely, the application falls back to the `application.properties` default of **port 8085**.

Generate a BCrypt password hash:

```bash
# Python (bcrypt must be installed: pip install bcrypt)
python -c "import bcrypt; print(bcrypt.hashpw(b'yourpassword', bcrypt.gensalt()).decode())"

# Or htpasswd (Apache utils)
htpasswd -bnBC 12 "" yourpassword | tr -d ':\n'
```

### 3. Build and run

```bash
cd backend
mvn package -DskipTests
java -jar target/holiday-leave-assistant-1.0.0.jar
```

Or run directly with Maven (no JAR needed during development):

```bash
cd backend
mvn spring-boot:run
```

> ⚠️ After any change to `application.properties` you **must rebuild** the JAR.
> Spring Boot reads config from inside the packaged JAR, not from the source tree.

Open **http://localhost:8080** (when using the shipped `.env.example`) or **http://localhost:8085** (fallback default when no `.env` is present).

### 4. Upload your data

1. Log in with the credentials from your `.env`.
2. Open the sidebar and click the upload zone.
3. Upload one or more `eIndkomst vacation <year>.xlsx` files.
4. Start chatting.

---

## Docker

```bash
# Build image and start container
docker compose up --build

# Start in background
docker compose up -d --build

# Stop
docker compose down
```

The container is named `holiday-leave-assistant`, tagged as `holiday-leave-assistant:latest`, and exposes port **8080**. `FLASK_PORT=8080` is set automatically inside the container so the Spring Boot server binds on the same port that is exposed.

When Ollama runs on the host machine, set in `.env`:

```
LLM_BASE_URL=http://host.docker.internal:11434/v1
```

Data and reports are persisted in bind-mounted local directories:

| Host path | Container path | Contents |
|---|---|---|
| `./data` | `/app/data` | Master `.xlsx` files + uploads + working copies |
| `./reports` | `/app/reports` | Generated HTML leave reports |

A health check polls `http://localhost:8080/login` every 30 seconds (10 s timeout, 3 retries, 40 s start-up grace period) and marks the container healthy once it passes.

---

## Podman

```bash
# Install podman-compose (once)
pip install podman-compose   # v1.0.6+

# Build image and start container
podman-compose -f podman-compose.yml up -d --build

# Stop
podman-compose -f podman-compose.yml down
```

The Podman compose file uses `restart: always` (more reliably honoured than `unless-stopped` across `podman-compose` versions) and defines an explicit `app-net` bridge network.

**Rootless Podman — Ollama on the host:**

`127.0.0.1` inside a rootless Podman container does **not** map to the host. Use the special DNS name instead:

```
LLM_BASE_URL=http://host.containers.internal:11434/v1
```

**SELinux hosts (Fedora / RHEL / CentOS):**

The `podman-compose.yml` appends `:Z` to volume mounts, which relabels them for SELinux. Remove `:Z` if your host does not enforce SELinux.

---

## Render.com

Deploy to [Render.com](https://render.com) using the included `render.yaml` Blueprint with Docker runtime.

### Prerequisites

- A Render account (free tier works for the web service; Disks require **Starter plan** or above)
- A cloud LLM provider — Ollama cannot be reached from Render's network. Use [Groq](https://console.groq.com), [OpenAI](https://platform.openai.com), or [OpenRouter](https://openrouter.ai)

### One-click Blueprint deploy

1. Push your repository (including `render.yaml`) to GitHub or GitLab.
2. In the Render dashboard → **New → Blueprint** → connect your repo.
3. Render reads `render.yaml` and creates the web service and two persistent disks automatically.
4. Before the first deploy, open the service's **Environment** tab and set the two secret variables:

| Variable | Where to set | Value |
|---|---|---|
| `LOGIN_PASSWORD_HASH` | Render dashboard → Environment | BCrypt hash of your login password |
| `OPENAI_API_KEY` | Render dashboard → Environment | Your LLM provider API key |

5. Trigger a manual deploy or push a commit — Render builds the Docker image and starts the service.

### Port binding

Render injects a `PORT` environment variable and routes all external HTTPS traffic to it. The `render.yaml` forwards `PORT` to `FLASK_PORT` so Spring Boot binds on the correct port automatically. No changes to `application.properties` or the `Dockerfile` are needed.

### Persistent storage

Two **Render Disks** are mounted at `/data` and `/reports`:

| Disk | Mount path | Contents |
|---|---|---|
| `holiday-data` | `/data` | Uploaded `.xlsx` files + working copies |
| `holiday-reports` | `/reports` | Generated HTML leave reports |

> ⚠️ Render's free tier does **not** include persistent disks. Upgrade to **Starter** to retain data across deploys and restarts.

### LLM configuration for Render

Since Ollama is not accessible from Render's network, update these two env vars in the Render dashboard (or edit `render.yaml` directly):

```
LLM_BASE_URL=https://api.groq.com/openai/v1
LLM_MODEL=llama-3.3-70b-versatile
OPENAI_API_KEY=<your-groq-api-key>
```

Any [OpenAI-compatible provider](#llm-provider-configuration) works — Groq, OpenAI, OpenRouter, IBM Watsonx.

### render.yaml reference

```
holiday-leave-assistant/render.yaml
```

Key fields:

| Field | Value | Notes |
|---|---|---|
| `runtime` | `docker` | Uses the project `Dockerfile` |
| `dockerfilePath` | `./holiday-leave-assistant/Dockerfile` | Relative to repo root |
| `dockerContext` | `./holiday-leave-assistant` | Build context containing `backend/` |
| `healthCheckPath` | `/login` | Render polls this path to confirm readiness |
| `plan` | `starter` | Minimum plan that supports persistent disks |
| `disk[0].mountPath` | `/data` | Must match `DATA_DIR` env var |
| `disk[1].mountPath` | `/reports` | Must match `REPORT_OUTPUT_DIR` env var |

---

## IBM Cloud Code Engine

Deploy to [IBM Cloud Code Engine](https://cloud.ibm.com/codeengine) using the included `ibm-code-engine.yaml` manifest.
This configuration uses **ephemeral `/tmp` storage** — suitable for demos and development.
Uploaded `.xlsx` files and generated HTML reports are lost when the container restarts; re-upload your data file after each restart.

### Prerequisites

- An [IBM Cloud](https://cloud.ibm.com) account (Pay-As-You-Go or Subscription)
- IBM Cloud CLI with Code Engine and Container Registry plug-ins
- A cloud LLM provider — Ollama is not reachable from Code Engine. Use [IBM Watsonx](https://www.ibm.com/watsonx), [Groq](https://console.groq.com), [OpenAI](https://platform.openai.com), or [OpenRouter](https://openrouter.ai)

### 1. Install CLI and log in

```bash
curl -fsSL https://clis.cloud.ibm.com/install/linux | sh
ibmcloud plugin install code-engine container-registry
ibmcloud login --sso
ibmcloud target -r us-south -g Default
```

### 2. Create a Code Engine project

```bash
ibmcloud ce project create --name holiday-leave-assistant
ibmcloud ce project select  --name holiday-leave-assistant
```

### 3. Build and push the image to ICR

```bash
ibmcloud cr login
ibmcloud cr namespace-add <YOUR_NAMESPACE>

docker build -t icr.io/<YOUR_NAMESPACE>/holiday-leave-assistant:latest .
docker push     icr.io/<YOUR_NAMESPACE>/holiday-leave-assistant:latest
```

### 4. Create the registry pull-secret

```bash
ibmcloud ce registry create \
  --name icr-secret \
  --server icr.io \
  --username iamapikey \
  --password <IBM_CLOUD_API_KEY>
```

### 5. Create the Secret (sensitive values)

```bash
ibmcloud ce secret create --name holiday-secrets \
  --from-literal LOGIN_PASSWORD_HASH="<bcrypt-hash>" \
  --from-literal FLASK_SECRET_KEY="$(openssl rand -hex 32)" \
  --from-literal OPENAI_API_KEY="<your-llm-api-key>"
```

Generate a BCrypt hash:

```bash
python -c "import bcrypt; print(bcrypt.hashpw(b'yourpassword', bcrypt.gensalt()).decode())"
```

### 6. Create the ConfigMap (non-sensitive values)

```bash
ibmcloud ce configmap create --name holiday-config \
  --from-literal FLASK_PORT="8080" \
  --from-literal LLM_BASE_URL="https://api.groq.com/openai/v1" \
  --from-literal LLM_MODEL="llama-3.3-70b-versatile" \
  --from-literal LLM_TEMPERATURE="0.0" \
  --from-literal LLM_MAX_TOKENS="1024" \
  --from-literal LOGIN_USERNAME="admin" \
  --from-literal SYNC_INTERVAL_SECONDS="30" \
  --from-literal PERMANENT_SESSION_LIFETIME="3600" \
  --from-literal LOG_LEVEL="INFO" \
  --from-literal WATSONX_PROJECT_ID=""
```

For IBM Watsonx replace the `LLM_*` lines with:

```
--from-literal LLM_BASE_URL="https://us-south.ml.cloud.ibm.com/ml/v1"
--from-literal LLM_MODEL="meta-llama/llama-3-3-70b-instruct"
--from-literal WATSONX_PROJECT_ID="<your-watsonx-project-id>"
```

### 7. Deploy with the manifest

```bash
ibmcloud ce application create --file ibm-code-engine.yaml
```

To update an already-deployed application:

```bash
ibmcloud ce application update --file ibm-code-engine.yaml
```

### 8. Get the public URL

```bash
ibmcloud ce application get --name holiday-leave-assistant
```

Code Engine provisions a public HTTPS URL automatically, e.g.:
`https://holiday-leave-assistant.<hash>.us-south.codeengine.appdomain.cloud`

### CI/CD — GitHub Actions

The included `.github/workflows/deploy-code-engine.yml` automates the full build-push-deploy cycle on every push to `main`.

Configure these in your GitHub repository **Settings → Secrets and variables**:

| Type | Name | Value |
|---|---|---|
| Secret | `IBM_CLOUD_API_KEY` | IBM Cloud API key with Code Engine Developer + ICR Writer roles |
| Variable | `IBM_CLOUD_REGION` | e.g. `us-south` |
| Variable | `ICR_NAMESPACE` | Your ICR namespace |
| Variable | `CE_PROJECT_NAME` | `holiday-leave-assistant` |
| Variable | `CE_APP_NAME` | `holiday-leave-assistant` |

### Ephemeral storage note

`DATA_DIR` is set to `/tmp/data` and `REPORT_OUTPUT_DIR` to `/tmp/reports`.
Both directories are created automatically when the application first writes to them.
All data is lost on container restart — this configuration is intended for **demos and development** only.
For production use, migrate to IBM Cloud Object Storage volume mounts (Option A in the deployment plan).

### ibm-code-engine.yaml reference

Key fields in `ibm-code-engine.yaml`:

| Field | Value | Notes |
|---|---|---|
| `containerPort` | `8080` | Must match `FLASK_PORT` |
| `min-scale` | `1` | Keeps one instance warm; avoids cold starts and preserves in-memory session |
| `max-scale` | `3` | Horizontal headroom for availability |
| `resources.limits.cpu` | `1` | Adequate for Spring Boot steady-state |
| `resources.limits.memory` | `2G` | Covers JVM heap (`-Xmx512m`) + POI + overhead |
| `DATA_DIR` | `/tmp/data` | Ephemeral — lost on restart |
| `REPORT_OUTPUT_DIR` | `/tmp/reports` | Ephemeral — lost on restart |
| `readinessProbe.path` | `/login` | HTTP 200 once Spring Boot is fully started |

---

## Environment Variables

All variables can be set in `.env` (copy from `.env.example`) or passed directly as environment variables. Values in `.env` override `application.properties` defaults.

| Variable | Required | Default | Description |
|---|---|---|---|
| `FLASK_SECRET_KEY` | | *(see .env.example)* | Session signing key. Generate with `openssl rand -hex 32` |
| `LOGIN_USERNAME` | | `admin` | Login username |
| `LOGIN_PASSWORD_HASH` | ✅ | — | BCrypt hash of the login password |
| `OPENAI_API_KEY` | | `""` | API key for cloud LLM providers. Ollama ignores this but the value must be non-empty for the adapter's availability check to pass when using cloud providers |
| `LLM_BASE_URL` | | `http://127.0.0.1:11434/v1` | Any OpenAI-compatible endpoint |
| `LLM_MODEL` | | `llama3.2` | Model name served by the endpoint |
| `LLM_TEMPERATURE` | | `0.0` | Sampling temperature (0 = deterministic) |
| `LLM_MAX_TOKENS` | | `1024` | Maximum response tokens |
| `WATSONX_PROJECT_ID` | | `""` | IBM Watsonx project ID (leave empty if not using Watsonx) |
| `DATA_DIR` | | `data` | Directory for master `.xlsx` files and uploads |
| `REPORT_OUTPUT_DIR` | | `reports` | Directory where generated HTML reports are saved |
| `SYNC_INTERVAL_SECONDS` | | `300` *(app default)* / `30` *(.env.example)* | How often `SyncService` syncs working copies back to master |
| `FLASK_PORT` | | `8085` *(app default)* / `8080` *(.env.example & compose)* | Server port |
| `PERMANENT_SESSION_LIFETIME` | | `3600` | Session timeout in seconds |
| `LOG_LEVEL` | | `INFO` | Logging level for `com.holidayleave.*` |

> **Port note:** `application.properties` defaults `FLASK_PORT` to `8085`. The shipped `.env.example` and both compose files override it to `8080`. If you run `java -jar` without any `.env`, the server starts on **8085**.

---

## LLM Provider Configuration

The app communicates with any **OpenAI-compatible** chat completions endpoint. Switch provider by setting `LLM_BASE_URL` and `LLM_MODEL` in `.env`.

| Provider | `LLM_BASE_URL` | Example `LLM_MODEL` |
|---|---|---|
| **Ollama** (default, local) | `http://127.0.0.1:11434/v1` | `llama3.2` |
| **Ollama in Docker** | `http://host.docker.internal:11434/v1` | `llama3.2` |
| **Ollama in rootless Podman** | `http://host.containers.internal:11434/v1` | `llama3.2` |
| **OpenAI** | `https://api.openai.com/v1` | `gpt-4o-mini` |
| **Groq** | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` |
| **OpenRouter** | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` |
| **IBM Watsonx** | `https://<region>.ml.cloud.ibm.com/ml/v1` | `meta-llama/llama-3-3-70b-instruct` |

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/login` | Login page |
| `POST` | `/login` | Authenticate |
| `GET` | `/logout` | Sign out |
| `POST` | `/api/chat` | Chat — analytical query or wizard step |
| `POST` | `/api/clear-history` | Clear conversation history |
| `POST` | `/api/upload` | Upload an `.xlsx` planner file |
| `GET` | `/api/files` | List all known Excel files |
| `POST` | `/api/switch-file` | Switch the active Excel file |
| `GET` | `/api/employees` | List employees in the active file |
| `GET` | `/api/years` | List available years in the active file |
| `POST` | `/api/vacations` | Add a vacation entry directly (non-wizard) |
| `DELETE` | `/api/vacations` | Delete a vacation entry directly (non-wizard) |
| `GET` | `/api/vacation-types` | List all configured leave types |
| `POST` | `/api/vacation-types` | Add a new leave type |
| `PUT` | `/api/vacation-types/{code}` | Update an existing leave type |
| `GET` | `/api/reports/{filename}` | Serve a generated HTML report |
| `GET` | `/api/sync-status` | Background sync status |

### Chat request / response

```json
// POST /api/chat
{ "message": "How many days has Alice taken in 2026?" }

// Response (analytical)
{ "reply": "Alice has taken 14 days in 2026 ...", "type": "text" }

// Response (wizard prompt)
{ "reply": "Please confirm:\n* Employee: ...", "type": "vacation_prompt" }

// Response (report)
{ "reply": "Report generated.", "type": "report" }
```

---

## Key Design Notes

| Aspect | Detail |
|---|---|
| **Single-tenant** | One login account, one agent singleton, session-keyed wizard state |
| **Read-on-every-request** | Leave records are never cached; every chat call reads fresh from the master `.xlsx` |
| **Working copy / master separation** | Writes go to `data/working/`, synced back to `data/` by `SyncService` at a configurable interval |
| **Atomic writes** | All file writes use a temp file + atomic rename to prevent corruption |
| **Weekend exclusion** | Saturday/Sunday cells are never written or counted in day totals |
| **LLM grounding** | System prompt strictly forbids use of knowledge outside the provided data context |
| **3-pass fuzzy name matching** | Employee names resolved via: exact substring → token overlap → fuzzy score |
| **Conversation history** | Last 10 turns (20 messages) kept in `AppState`, cleared on new chat |
| **Date display format** | All dates shown in chat and reports use `dd MMM yyyy` (e.g. `01 Aug 2026`) |
| **Report design** | Self-contained HTML: dark navy header, 6 KPI cards, utilization progress bar, 3 Chart.js charts (bar / doughnut / trend line), colour-coded leave-type badges, responsive layout, print stylesheet |
| **Auto-focus** | Chat input regains keyboard focus automatically after every agent response |

---

## Troubleshooting

**`localhost/172.17.0.2:11434 — Connection refused`**

This is Java printing the DNS resolution result alongside the hostname. `172.17.0.2` is the Docker bridge IP that `localhost` resolves to when Docker Desktop is installed. Fix: use `127.0.0.1` instead of `localhost` in `LLM_BASE_URL`.

---

**App starts but all chat requests return 502**

The LLM endpoint is unreachable. Verify:
- Ollama is running: `ollama list`
- `LLM_BASE_URL` in `.env` is correct and reachable from where the app is running
- If running in Docker, use `host.docker.internal` instead of `127.0.0.1`
- If running in rootless Podman, use `host.containers.internal` instead of `127.0.0.1`

---

**Port conflict on startup**

The effective port depends on how you run the app:

| Run method | Effective port |
|---|---|
| `java -jar` with no `.env` | **8085** (`application.properties` default) |
| `java -jar` with `.env.example` copied to `.env` | **8080** (`FLASK_PORT=8080` in `.env.example`) |
| `docker compose up` | **8080** (set by `docker-compose.yml` environment block) |
| `podman-compose up` | **8080** (set by `podman-compose.yml` environment block) |

To change the port, update `FLASK_PORT` in `.env` and update the `ports` mapping in `docker-compose.yml` / `podman-compose.yml` to match.

---

**`LOGIN_PASSWORD_HASH` — authentication always fails**

Ensure there are no leading/trailing spaces around the hash in `.env`. The hash must start with `$2b$` or `$2a$`. Re-generate with:

```bash
htpasswd -bnBC 12 "" yourpassword | tr -d ':\n'
```

---

**Excel file not parsed / zero employees shown**

The reader expects the eIndkomst vacation planner format. Column headers and sheet names must match the expected layout. Check the application log (`LOG_LEVEL=DEBUG`) for parse errors.

---

**Changes to `application.properties` have no effect**

Spring Boot reads configuration from inside the packaged JAR. After editing `application.properties`, rebuild:

```bash
cd backend && mvn package -DskipTests
```

---

**`java.lang.UnsupportedClassVersionError` on startup**

The Docker image uses JDK 21 (`eclipse-temurin:21`). Running `java -jar` locally requires **Java 21 or later**. Check your version with `java -version` and install [Eclipse Temurin 21](https://adoptium.net) if needed.
