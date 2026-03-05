# Notes & Todos App

A full-stack multi-user notes, todos, and recipes application with a React frontend and a Node/Express + PostgreSQL backend.

## Stack

- **Frontend**: React 18 (Create React App), single-page app
- **Backend**: Node.js + Express, PostgreSQL via `pg`
- **Auth**: JWT (web UI) + per-user API keys (REST API)
- **Passwords**: Argon2id for new hashes; bcrypt verified for legacy hashes
- **File storage**: AWS S3 for recipe PDFs in production; local disk (`backend/uploads/recipes/`) in dev
- **Deployment**: AWS ECS Fargate + RDS Postgres. See `deploy/AWS_DEPLOYMENT.md`.

---

## Local Development

The backend requires a running PostgreSQL instance. In dev, run one locally via Docker.

### Prerequisites

- Node.js v22+
- Docker (for local Postgres)

### 1. Start local Postgres

```bash
docker run -d --name notes-todos-postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=notes_todos \
  -p 5432:5432 \
  postgres:17-alpine
```

If the container already exists but is stopped:

```bash
docker start notes-todos-postgres
```

### 2. Install dependencies

```bash
# Backend
cd backend && npm install && cd ..

# Frontend
npm install
```

### 3. Start the backend

```bash
cd backend
DATABASE_URL='postgres://postgres:postgres@localhost:5432/notes_todos' \
PGSSL=false \
JWT_SECRET='dev-secret-change-me' \
PORT=3001 \
node server.js
```

Or use the helper script (handles Docker start + Node `--watch` auto-reload):

```bash
bash scripts/dev_backend.sh
```

Health check: http://localhost:3001/healthz

### 4. Start the frontend

```bash
npm start          # opens http://localhost:3000
```

Or via the helper script (suppresses auto-open, loops on exit):

```bash
bash scripts/dev_frontend.sh
```

### Environment variables (backend)

| Variable | Required | Description |
|---|---|---|
| `DATABASE_URL` | Yes | Postgres connection string |
| `PGSSL` | No | Set to `true` for SSL (needed for RDS) |
| `JWT_SECRET` | Recommended | Stable secret so tokens survive restarts |
| `PORT` | No | Defaults to `3001` |
| `ANTHROPIC_API_KEY` | No | Fallback Anthropic key for ingredient extraction (users can set their own via Admin) |
| `ENCRYPTION_KEY` | Recommended | Secret for encrypting API keys at rest (AES-256-GCM). Falls back to deriving from `JWT_SECRET` |
| `S3_BUCKET` | No | Enables S3 storage for recipe PDFs |
| `CORS_ORIGIN` | No | Restrict CORS in prod (e.g. `https://yourdomain.com`) |

### Environment variables (frontend)

Create `.env.development` in the project root:

```bash
REACT_APP_INSTANCE=dev   # dev styling (red primary buttons)
```

> `.env.development` is gitignored — do not commit it.

---

## Project Structure

```
notes-todos/
├── backend/
│   ├── server.js          # Express app and all API routes
│   ├── db.js              # Postgres pool (pg)
│   ├── migrate.js         # Idempotent schema migrations (runs on startup)
│   ├── crypto-utils.js    # AES-256-GCM encryption + HMAC hashing for API keys
│   ├── s3.js              # S3 helpers for recipe PDF storage
│   ├── uploads/recipes/   # Local PDF storage (dev only, gitignored)
│   └── package.json
├── src/
│   ├── App.js                              # Orchestrator: all feature state, handlers, layout shell
│   ├── App.css                             # Component styles
│   ├── index.js                            # React entry point (wraps App in AuthProvider)
│   ├── index.css                           # Global styles
│   ├── setupProxy.js                       # CRA dev proxy config
│   ├── context/
│   │   └── AuthContext.js                  # Auth: token, authFetch, login, register, logout
│   ├── components/
│   │   ├── AuthPage.js                     # Login + register forms
│   │   ├── AboutModal.js                   # About modal (shared across auth + main app)
│   │   ├── ClaudeModal.js                  # Claude co-author profile modal
│   │   ├── AdminPanel.js                   # Password reset + API key management
│   │   ├── notes/
│   │   │   ├── NotesTab.js                 # Sort toolbar, composer, note grid, note modal
│   │   │   └── NoteCard.js                 # Individual note card (pinned + regular)
│   │   ├── todos/
│   │   │   └── TodosTab.js                 # Category tabs, add form, todo list
│   │   └── recipes/
│   │       └── RecipesTab.js               # Recipe form, viewer, recipe list
│   └── utils/
│       ├── renderWithLinks.js              # Converts URLs in text to clickable links
│       └── sortNotes.js                    # Note sort logic (date/alpha, asc/desc)
├── public/
│   ├── index.html
│   └── index.html
├── scripts/
│   ├── dev_backend.sh     # Dev backend runner (Docker + node --watch)
│   ├── dev_frontend.sh    # Dev frontend runner
│   └── owasp-scan.sh      # OWASP ZAP scanner (baseline + full)
├── deploy/
│   ├── AWS_DEPLOYMENT.md  # Production deployment guide
│   ├── LOCAL_DEV.md       # Local dev setup details
│   ├── deploy.sh          # ECS deploy script
│   └── ecs-taskdef.json   # ECS task definition
└── package.json           # Frontend dependencies + semgrep scripts
```

---

## Features

- Multi-user authentication (register, login, JWT)
- Per-user data isolation: notes, todos, API keys, recipes
- **Notes**: create, edit, delete, pin, reorder, sort
- **Todos**: create, complete, delete, categorise
- **Recipes**: create with notes and a PDF attachment; ingredient extraction via Claude
- **Account**: change password, manage App API keys, configure Anthropic API key
- REST API with `x-api-key` authentication (per user)
- Per-user Anthropic API key storage for ingredient extraction (BYOK)
- Dark mode UI

---

## Database Schema

Tables (created and migrated automatically on startup):

- `users` — accounts
- `notes` — per-user notes with pin + sort order
- `todos` — per-user todos with category
- `todo_categories` — persisted custom categories per user
- `api_keys` — named API keys per user
- `recipes` — recipe entries with optional PDF and ingredient metadata
- `ingredients` — cached OCR ingredient results per recipe

---

## Authentication

**Web UI** — JWT Bearer token:
- `POST /api/register` — create account (username ≥ 3 chars, password ≥ 6 chars)
- `POST /api/login` — returns JWT token (7-day expiry)
- Token stored in `localStorage`; pass as `Authorization: Bearer <token>`

**REST API** — API key:
- Create keys from the Admin panel (App API Keys section)
- Pass as `x-api-key: YOUR_KEY` header
- Keys are scoped to the creating user

**Anthropic API key** (optional, per-user):
- Each user can paste their own Anthropic API key in Admin settings
- Enables the "Create ingredient list" feature on recipes (PDF/text OCR via Claude)
- Keys are validated against the Anthropic API on save
- If no per-user key is set, falls back to the server-level `ANTHROPIC_API_KEY` env var

---

## API Endpoints

### Auth (no token required)

```
POST /api/register
POST /api/login
```

### Web UI endpoints (JWT required)

```
POST   /api/reset-password
GET    /api/notes
POST   /api/notes
PUT    /api/notes/:id
DELETE /api/notes/:id
GET    /api/todos
POST   /api/todos
PUT    /api/todos/:id
DELETE /api/todos/:id
GET    /api/keys
POST   /api/keys
DELETE /api/keys/:id
GET    /api/anthropic-key
PUT    /api/anthropic-key
DELETE /api/anthropic-key
GET    /api/recipes
POST   /api/recipes
PUT    /api/recipes/:id
DELETE /api/recipes/:id
GET    /api/recipes/:id/pdf
POST   /api/recipes/:id/pdf
DELETE /api/recipes/:id/pdf
POST   /api/recipes/:id/ingredients
GET    /api/recipes/:id/ingredients
POST   /api/recipes/:id/add-to-todos
```

### REST API (x-api-key required)

**Notes:**
```
GET    /api/v1/notes            # supports ?search=
GET    /api/v1/notes/:id
POST   /api/v1/notes
PUT    /api/v1/notes/:id
DELETE /api/v1/notes/:id
```

**Todos:**
```
GET    /api/v1/todos            # supports ?search= and ?completed=true/false
GET    /api/v1/todos/:id
POST   /api/v1/todos
PUT    /api/v1/todos/:id
PATCH  /api/v1/todos/:id/complete
PATCH  /api/v1/todos/:id/incomplete
DELETE /api/v1/todos/:id
```

See `API_DOCUMENTATION.md` for full request/response examples.

---

## Security

### Passwords
- **Argon2id** for all new password hashes (64 MiB memory, 3 iterations, parallelism 1)
- Legacy bcrypt hashes are verified on login and automatically upgraded to Argon2id
- Minimum password length enforced (6 characters)

### API key storage
- **App API keys** and **Anthropic API keys** are encrypted at rest using AES-256-GCM
- Encryption key derived from the `ENCRYPTION_KEY` env var (falls back to `JWT_SECRET` via HKDF)
- App API keys use an HMAC-SHA256 hash for fast authentication lookups — the raw key is never compared directly in the database
- Existing plaintext keys are automatically encrypted on server startup (idempotent migration)

### Transport and headers
- Helmet (CSP, HSTS in prod, referrer policy, permissions policy)
- CORS restricted via `CORS_ORIGIN` in production
- Rate limiting on auth endpoints

### Input validation
- Path traversal guard on recipe PDF filenames
- JWT token expiry (7 days)

### Static analysis (SAST)
- Semgrep runs automatically before each deploy (`npm run semgrep`) with JavaScript, React, and secrets rulesets
- Deploy fails if Semgrep finds issues

### Dynamic analysis (DAST)
- OWASP ZAP scans via Docker (`scripts/owasp-scan.sh`)
- **Baseline scan** (passive, safe for prod): `npm run owasp:baseline https://yourapp.com`
- **Full scan** (active, staging/local only): `npm run owasp:full http://localhost:3001`
- Deploy with `--scan` flag to run a baseline scan after deployment: `./deploy/deploy.sh --scan`
- Reports output to `reports/owasp/` (gitignored)

---

## Production Deployment

See `deploy/AWS_DEPLOYMENT.md` for the full ECS + RDS + S3 setup.

Before deploying, copy and fill in `deploy/aws.env`:

```bash
cp deploy/aws.env deploy/aws.env   # edit in place
```

| Variable | Required | Description |
|---|---|---|
| `AWS_ACCOUNT_ID` | Yes | Your 12-digit AWS account ID |
| `AWS_REGION` | Yes | AWS region (default: `eu-west-2`) |
| `AWS_PROFILE` | Yes | AWS CLI profile to use |
| `ECR_REPO` | Yes | ECR repository name |
| `ECS_CLUSTER` | Yes | ECS cluster name |
| `ECS_SERVICE` | Yes | ECS service name |
| `TASK_FAMILY` | Yes | ECS task definition family name |
| `DOMAIN` | No | Your domain (sets `CORS_ORIGIN` on the task) |
| `S3_BUCKET` | No | Recipe PDFs bucket name |

Then run:

```bash
./deploy/deploy.sh
```

---

## Development Notes

- The frontend uses the CRA proxy (`package.json` `"proxy"`) so `/api/...` calls in dev hit `localhost:3001` automatically
- `REACT_APP_INSTANCE=dev` enables dev button styling; on `localhost` this is inferred automatically if the env var is not set
- Semgrep security scanning: `npm run semgrep` (requires `semgrep` installed)
- Backend migrations are idempotent — safe to restart at any time

---

## Troubleshooting

**Backend won't start:** Check `DATABASE_URL` is set and Postgres is running (`docker ps`).

**Frontend can't reach backend:** Confirm backend is on port 3001 and the CRA proxy is active (dev only). For access from another device, set `REACT_APP_API_URL=http://<host>:3001/api`.

**Tokens invalidated on restart:** Set a stable `JWT_SECRET` env var.

**Recipe PDFs not loading:** In dev, check `backend/uploads/recipes/`. In prod, verify `S3_BUCKET` and IAM permissions.
