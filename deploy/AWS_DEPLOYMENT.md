# Deploy Notes & Todos to AWS (serverless) — UK (London)

The app runs **serverless** in eu-west-2:

- **Frontend** (Create React App build) is served as static files from **S3** via **CloudFront**.
- **Backend** (the existing Express app) runs as an **arm64 Lambda container image** via the
  **AWS Lambda Web Adapter**, fronted by an **API Gateway HTTP API**.
- **Data** lives in **S3** (JSON collections + recipe PDFs). There is no RDS, no VPC, no ALB.

CloudFront routes `/api/*` to API Gateway and everything else to the S3 static bucket, so the app is
single-origin (no CORS in normal use). See `LAMBDA_REARCH.md` for why it was moved off ECS/RDS and
the trade-offs (notably the single-writer datastore).

---

## 0) Architecture

```
                     news/todo.promptbuilt.co.uk
                                |
                          CloudFront (E3MNPLNDNY4MOK)
                          |                       |
            default -> S3 static           /api/* -> API Gateway (notes-todos-api)
   notes-todos-web-...-bucket                     |
   (React build)                            Lambda notes-todos-web
                                            (container image, arm64, reserved concurrency 1)
                                                  |
                                    S3 data bucket: promptbuilt-todo-recipes-...
                                    (data/*.json collections + recipes/<userId>/*.pdf)
```

Deep links are handled by the `notes-todos-spa` CloudFront Function (rewrites extensionless paths to
`/index.html`). No custom error responses, so API status codes pass through untouched.

## 1) Resources (already provisioned)

| Piece | Value |
|---|---|
| Region / account | eu-west-2 / 084953646311 |
| Lambda | `notes-todos-web` (image, arm64, timeout 30s, mem 1024, **reserved concurrency 1**) |
| Lambda role | `notes-todos-lambda-role` (S3 data bucket + `secretsmanager:GetSecretValue` on the JWT secret) |
| ECR repo | `notes-todos-lambda` |
| API Gateway | HTTP API `notes-todos-api` (`nvdknr4itl`), `$default` route -> Lambda proxy |
| CloudFront | `E3MNPLNDNY4MOK` (`d37tinvla50dhd.cloudfront.net`) + function `notes-todos-spa` |
| Static bucket | `notes-todos-web-084953646311-eu-west-2` (private, OAC) |
| Data bucket | `promptbuilt-todo-recipes-084953646311-eu-west-2` |
| ACM cert | us-east-1, covers `news`/`todo.promptbuilt.co.uk` |
| Secret | `notes-todos/JWT_SECRET` |
| DNS | A/AAAA aliases for news + todo -> CloudFront (zone `promptbuilt.co.uk`) |

IDs are also recorded in `deploy/aws.env` (gitignored, local only).

## 2) Prerequisites
- AWS CLI configured for profile `notes-todos-prod` (`aws sts get-caller-identity`).
- **Docker running** (the backend deploys as a container image).
- Node/npm (for the React build).

## 3) Backend environment (set on the Lambda)
- `JWT_SECRET` — signing secret **and** the source of the data-encryption key in `crypto-utils.js`.
  It is set as a Lambda env var from Secrets Manager; keep it identical to the old value or existing
  encrypted data (stored API keys) will not decrypt.
- `S3_BUCKET=promptbuilt-todo-recipes-084953646311-eu-west-2` — data + uploads. Its presence is what
  switches the app from local-disk fallback to S3 (required on Lambda; the filesystem is read-only).
- `NODE_ENV=production`
- `CORS_ORIGIN=https://todo.promptbuilt.co.uk` (only matters for any cross-origin API use).
- `AWS_REGION` is provided by the Lambda runtime; do not set it.

## 4) Deploy

```bash
./deploy/deploy.sh
```

This builds the React app to S3, builds + pushes the arm64 image to ECR, updates the Lambda, and
invalidates CloudFront. It reads settings from `deploy/aws.env`.

To change the backend only, the same script is fine (it always does both). The image must be a plain
single-arch Docker image, so the build uses `--provenance=false --sbom=false` (Lambda rejects the OCI
attestation manifest buildx adds by default).

## 5) Data model
`backend/datastore.js` loads S3 JSON collections (`data/users.json`, `data/notes.json`, ...) into
memory at cold start and persists writes back as whole files. Recipe PDFs go to
`recipes/<userId>/<uuid>-<name>.pdf` in the same bucket, keyed from `recipes.pdf_filename`.

Because a Lambda instance holds the whole dataset in memory, **reserved concurrency is pinned to 1**
so only one writer exists at a time. Do not raise it without moving to a per-item store (DynamoDB,
"Path B" in `LAMBDA_REARCH.md`), or concurrent whole-file writes will clobber each other.

## 6) Operations
- **Cold start ~3.5s** when idle (the instance reloads all data from S3); warm requests are ~ms. The
  server listens immediately and gates `/api` on the load, so `/healthz` is up at once.
- **Logs:** CloudWatch `/aws/lambda/notes-todos-web`.
- **Rollback:** `aws lambda update-function-code` to a previous image tag, or re-run `deploy.sh` from
  an earlier commit. (The ECS/ALB stack has been decommissioned; there is no ALB fallback.)
- **Cost:** all pay-per-use; roughly $2-3/month at current traffic.

## 7) Local run (smoke test)
```bash
# frontend + backend served together, local disk fallback (no S3):
cd backend && cp .env.example .env   # set JWT_SECRET; leave S3_BUCKET unset for disk mode
node server.js
curl -sS http://localhost:3001/healthz
```
