# notes-todos: Lambda re-architecture scope

Goal: remove the ~$50/month of always-on ECS overhead (ALB + public IPv4 +
Fargate) by moving to a serverless architecture. Cost after: roughly $1-3/month.

_Scoped 2026-09-01 from the actual code._

## What the app is
- **Frontend:** Create React App SPA (`react-scripts build` -> `build/`).
- **Backend:** one Express app (`backend/server.js`, ~1,560 lines): JWT auth,
  notes, todos, categories, recipes, API keys, image->PDF, file uploads.
- **Data:** `backend/datastore.js` — an in-memory store hydrated from S3 JSON
  files under `data/`. No Postgres anymore (the `DATABASE_URL`/`PGSSL` task-def
  secrets are leftovers from the retired `db.js`; RDS is already gone bar a
  stray snapshot). Secrets used: `JWT_SECRET`, plus the key(s) in
  `crypto-utils.js`. `S3_BUCKET` for data + uploads.
- **Native deps:** `argon2` and `sharp` are native; `bcryptjs`, `pdf-lib`,
  `jsonwebtoken`, `express` etc. are pure JS.

## Target architecture
```
CloudFront
 ├─ /            -> S3 (React build, static)          [pennies]
 └─ /api/*       -> Lambda (the Express app)          [per-request]
Lambda -> S3 (data/ + uploads/) and Secrets Manager   [no VPC needed]
```
No VPC, no NAT, no ALB, no public IPv4. Because data is in S3 and secrets in
Secrets Manager (both reachable from a non-VPC Lambda), the function runs
outside a VPC, which also avoids ENI cold-start penalties.

## The one real problem: the datastore concurrency model
`datastore.js` assumes a single always-on process:
1. Loads all data into memory once at `init()`.
2. Reads synchronously from memory.
3. Persists writes by rewriting the whole collection file in S3, several of
   them **fire-and-forget** (`_save(...)`, not awaited).

On Lambda this breaks in two ways:
- **Concurrent instances** each hold their own in-memory copy. Whole-file
  overwrites mean two instances writing different records = **lost updates**
  (last writer clobbers the file).
- **Fire-and-forget writes** may never flush: Lambda can freeze the instance
  once the response is sent, dropping the pending `PutObject`.

Two ways to handle it:

### Path A — Minimal change (recommended for a single-user app)
Keep the datastore, make it Lambda-safe by constraint:
- Wrap the Express app with the **AWS Lambda Web Adapter** (run the server
  unchanged behind a Function URL) or `@codegenie/serverless-express`.
- **Reserved concurrency = 1** so only one instance runs at a time, preserving
  the single-writer assumption. Fine for a personal, low-traffic app; it
  serialises requests and cold starts reload from S3.
- **Make every write `await` its persist** (convert the fire-and-forget `_save`
  sites to awaited saves) so nothing is lost on freeze.
- **Package as a Lambda container image** to handle the native `argon2` +
  `sharp` binaries cleanly (up to 10 GB image).
- **multer -> memoryStorage** and push uploads to S3 (already have `s3.js`);
  no local `uploads/` dir on Lambda.
- Move the React `build/` to S3 + CloudFront; Lambda serves only `/api/*`.
- Estimate: ~1-2 days. Caveats: cold-start latency (~1-2 s incl. loading data
  from S3), concurrency capped at 1.

### Path B — Proper serverless data layer
Replace the in-memory/S3 store with **DynamoDB** (per-item writes, concurrency
-safe, no whole-file clobber, no full-data load on cold start). This removes the
concurrency cap and the cold-start data load, at the cost of rewriting
`datastore.js` around DynamoDB access patterns.
- Estimate: ~3-5 days. This is the "right" long-term answer if the app ever goes
  multi-user or higher traffic.

## Recommendation
Do **Path A** now: it kills the ~$50/month overhead with the least work and no
data-model rewrite, which suits a single-user app. Keep Path B on the shelf for
if/when concurrency or latency matters. The fire-and-forget->await fix is worth
doing regardless (it's a latent data-loss bug even on ECS during restarts).

## Cost impact
| | Now | After Path A |
|---|---|---|
| ALB | ~$20 | $0 |
| Public IPv4 | ~$15 | $0 |
| Fargate compute | ~$14 | $0 |
| Lambda + CloudFront + S3 | - | ~$1-3 |
| **notes-todos total** | **~$58** | **~$2** |

Combined with the cuts already made (architecture-svc, Container Insights),
notes-todos goes from ~$88/month to low single digits.

## Step plan (Path A)
1. Split static: `build/` -> new S3 bucket + CloudFront behaviour; `/api/*` ->
   Lambda origin (Function URL).
2. Containerise the backend (Dockerfile with Lambda Web Adapter; bundle argon2 +
   sharp for the Lambda runtime); push to ECR.
3. Create the Lambda (container image), role with S3 + Secrets access, env
   `JWT_SECRET`/`S3_BUCKET`/encryption key, **reserved concurrency 1**.
4. Convert fire-and-forget `_save` calls to awaited persistence.
5. Switch multer to memoryStorage -> S3.
6. Wire CloudFront -> Lambda Function URL for `/api/*`; point DNS at CloudFront.
7. Verify end-to-end, then tear down ECS service, ALB, target group, and the
   Postgres/DATABASE_URL leftovers.

## IMPLEMENTED 2026-09-01 (Path A) — LIVE

Serverless stack live at https://news.promptbuilt.co.uk and https://todo.promptbuilt.co.uk.
- **Lambda:** `notes-todos-web` (container image, arm64, reserved concurrency 1),
  role `notes-todos-lambda-role` (S3 + JWT secret). Runs the Express app via the
  AWS Lambda Web Adapter. `JWT_SECRET` set as an env var (same value as ECS, so
  encrypted data still decrypts).
- **API:** API Gateway HTTP API `notes-todos-api` (proxy to the Lambda). Chosen
  over a Lambda Function URL because the org SCP blocks public Function URLs and
  CloudFront-OAC + POST bodies is unreliable.
- **Static:** S3 `notes-todos-web-084953646311-eu-west-2` (React build).
- **CloudFront:** `E3MNPLNDNY4MOK` — default -> S3, `/api/*` -> API Gateway;
  SPA fallback via the `notes-todos-spa` CloudFront Function (no error-masking).
- **Cert:** ACM us-east-1 for news + todo. DNS aliases repointed off the ALB.

Code changes: listen before the S3 data load and gate `/api` on readiness
(cold-start fix); await the previously fire-and-forget password write; guard the
uploads mkdir behind `!S3_BUCKET` (read-only FS on Lambda).

Deploy with `deploy/deploy.sh`.

### Teardown pending (once authenticated flows are confirmed)
ECS service `notes-todos-svc`, cluster `notes-todos`, ALB `notes-todos-alb`,
target group `notes-todos-tg`. Optional: old ECR repo `notes-todos-app`, the dead
`notes-todos/DATABASE_URL` secret, and the ECS files (`ecs-taskdef.json`, old
`deploy.sh`).
