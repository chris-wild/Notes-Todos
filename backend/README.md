# Notes & Todos Backend (Postgres)

This backend now uses **PostgreSQL** via [`pg`](https://www.npmjs.com/package/pg).

## Environment

Create a `.env` (or export env vars) with at least:

```bash
DATABASE_URL=postgres://USER:PASSWORD@HOST:5432/DBNAME
# For AWS RDS you will usually need:
# PGSSL=true

# Optional but recommended for stable sessions across restarts:
# JWT_SECRET=some-long-random-string
```

See `.env.example` for a minimal template.

## Run

```bash
npm install
DATABASE_URL=postgres://... PGSSL=true npm start
```

On startup the server runs an idempotent migration that creates/updates tables:
- `users`, `notes`, `todos`, `api_keys`, `recipes`
- plus relevant indexes

## Notes

- Uploads path is unchanged: `backend/uploads/recipes`.
- Auth behavior is unchanged: JWT for web UI endpoints and `x-api-key` for `/api/v1/*` endpoints.
