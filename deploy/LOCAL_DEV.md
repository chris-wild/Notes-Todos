# Local development (Notes & Todos)

## Why you couldn't create notes/todos
The backend now uses **Postgres**. The AWS RDS instance is **not publicly accessible**, so the backend can't connect to it from your Mac.

Solution: run a local Postgres in Docker for dev.

---

## 1) Start local Postgres

```bash
docker run -d --name notes-todos-postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=notes_todos \
  -p 5432:5432 \
  postgres:17-alpine
```

(If it's already running, Docker will error; just skip.)

To stop/remove:
```bash
docker stop notes-todos-postgres
docker rm notes-todos-postgres
```

---

## 2) Start backend

```bash
cd backend
export DATABASE_URL='postgres://postgres:postgres@localhost:5432/notes_todos'
export PGSSL=false
export JWT_SECRET='dev-secret-change-me'
export PORT=3001
npm start
```

Health:
- http://localhost:3001/healthz

---

## 3) Start frontend

```bash
cd ..
npm start
```

UI:
- http://localhost:3000

---

## Notes
- In local dev we **do not** use S3; recipe PDFs stay on disk under `backend/uploads/recipes`.
- Prod uses RDS + S3; local uses Docker Postgres.
