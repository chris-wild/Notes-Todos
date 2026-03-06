# Deploy Notes & Todos App to AWS (RDS Postgres + ECS Fargate) — UK (London)

This guide packages the app as **one container**:
- React frontend is built at image build time and served by the backend (Express) in production.
- Backend connects to **AWS RDS Postgres** via `DATABASE_URL`.

Target region: **eu-west-2 (London)**.

---

## 0) Architecture (recommended)

- **RDS Postgres** (private subnets)
- **ECS Fargate service** (private subnets)
- **ALB** (public subnets) terminates TLS (ACM certificate)
- (Optional) **Route53** domain → ALB
- (Recommended for PDFs) **S3** for recipe PDF storage (see section 10)

Why: cheapest/cleanest “production” setup with correct networking + persistence.

---

## 1) Prerequisites

- AWS account + IAM user/role with rights for: ECR, ECS, EC2/VPC, ALB, RDS, Secrets Manager, CloudWatch Logs.
- AWS CLI configured:
  ```bash
  aws configure
  aws sts get-caller-identity
  ```
- Docker installed locally.

---

## 2) App configuration (env vars)

Backend runtime environment variables:

- `DATABASE_URL` (required)
  - Example:
    - `postgres://USER:PASSWORD@HOST:5432/DBNAME`
- `PGSSL` (recommended for RDS)
  - Set to `true` (or `1` or `require`) to enable TLS with `rejectUnauthorized:false`.
- `JWT_SECRET` (strongly recommended)
  - If unset, the server generates a random secret each boot (users will be logged out after redeploy).
- `CORS_ORIGIN` (optional)
  - For same-origin deployment behind ALB, you can leave this unset.
  - If you need it: comma-separated origins, e.g. `https://notes.example.com`.
- `PORT` (optional)
  - Defaults to `3001`.

---

## 3) Create RDS Postgres (London)

In AWS Console → **RDS → Create database**:

- Engine: **PostgreSQL**
- Templates: Production (or Dev/Test)
- Region: **eu-west-2**
- Connectivity:
  - **Do not make public**
  - Place in **private subnets**
- Security group:
  - Allow inbound **5432** only from the ECS tasks’ security group
- Credentials:
  - Store username/password in **Secrets Manager** (recommended)

After creation, note:
- Endpoint hostname
- Port (5432)
- DB name

---

## 4) Create ECR repository

```bash
aws ecr create-repository \
  --region eu-west-2 \
  --repository-name notes-todos-app
```

Log in Docker to ECR:
```bash
aws ecr get-login-password --region eu-west-2 \
| docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.eu-west-2.amazonaws.com
```

---

## 5) Build and push the Docker image

From repo root:
```bash
# from the repo root

# Build
docker build -t notes-todos-app:latest .

# Tag
docker tag notes-todos-app:latest <ACCOUNT_ID>.dkr.ecr.eu-west-2.amazonaws.com/notes-todos-app:latest

# Push
docker push <ACCOUNT_ID>.dkr.ecr.eu-west-2.amazonaws.com/notes-todos-app:latest
```

---

## 6) Create CloudWatch logs group

Console: CloudWatch → Logs → Log groups → create:
- `/ecs/notes-todos-app`

---

## 7) Create ECS cluster + task definition

### Task definition settings
- Launch type: **Fargate**
- CPU/Memory: start with **0.25 vCPU / 0.5–1GB**
- Container port: **3001**
- Log driver: awslogs → `/ecs/notes-todos-app`

### Environment/secrets
Use **Secrets Manager** for sensitive values.

Recommended:
- Store `DATABASE_URL` as a secret (single string), or store components and assemble.
- Store `JWT_SECRET` as a secret.

In the container env:
- `NODE_ENV=production`
- `PGSSL=true`

---

## 8) Create an ALB + HTTPS

1) Create **Application Load Balancer** in public subnets.
2) Listener:
   - 443 (HTTPS) with ACM certificate
   - Forward to a target group
3) Target group:
   - Type: IP
   - Port: 3001
   - Health check path: `/healthz`

---

## 9) Create ECS service

- Networking: private subnets
- Assign public IP: **off**
- Attach to ALB target group
- Desired tasks: 1 (start)

Security groups:
- ALB SG: allow inbound 443 from the internet.
- ECS task SG: allow inbound 3001 **from ALB SG only**.
- RDS SG: allow inbound 5432 **from ECS task SG only**.

Once running, visit your ALB DNS name. You should see the app UI.

---

## 10) Recipe PDF uploads on AWS (S3)

This project is now set up to store recipe PDFs in **S3**.

### How it works
- If `S3_BUCKET` is set, the backend:
  - uploads PDFs to `s3://<bucket>/recipes/<userId>/<uuid>-<originalName>`
  - stores the **S3 object key** in Postgres (`recipes.pdf_filename`)
  - streams the PDF back via: `GET /api/recipes/:id/pdf` (same endpoint as before)

### Required env vars (ECS task)
- `S3_BUCKET=your-bucket-name`
- `AWS_REGION=eu-west-2`

### IAM permissions (ECS task role)
Attach an IAM policy to the **task role** allowing:
- `s3:PutObject`
- `s3:GetObject`
- `s3:DeleteObject`

Scoped to your bucket/prefix, e.g. `arn:aws:s3:::YOUR_BUCKET/recipes/*`.

### Notes
- You do **not** need to set `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` in ECS if you use a task role.
- For local dev without S3, leave `S3_BUCKET` unset and it falls back to disk storage under `backend/uploads/recipes`.

---

## 11) Local “production-like” run (smoke test)

```bash
cd backend
cp .env.example .env
# Fill DATABASE_URL, PGSSL, JWT_SECRET
node server.js

curl -sS http://localhost:3001/healthz
```

---

## 12) Operational checklist

- Set `JWT_SECRET` (otherwise users get logged out on every deploy)
- Backups:
  - Enable automated RDS backups + snapshots
- Monitoring:
  - ECS service alarms (CPU/memory)
  - ALB 5xx alarm
- Security:
  - Don’t expose RDS publicly
  - Use Secrets Manager
  - Rotate secrets periodically

---

## 13) Typical next improvements

- Add DB migration tool (e.g. node-pg-migrate / drizzle / knex migrations) for versioned migrations.
- Add `/readyz` that checks DB connectivity.
- Add request logging with correlation ids.

## 14) One-command deploy (build + push + rollout)

This repo includes a helper script:

- `deploy/aws.env` — your deployment settings
- `deploy/deploy.sh` — builds the Docker image, pushes to ECR, registers a new ECS task definition revision, and rolls the service.

Usage:

```bash
cd notes-todos-app
./deploy/deploy.sh              # deploys :latest
./deploy/deploy.sh v2026-02-08  # optional custom tag
```

The script also sets `CORS_ORIGIN=https://<DOMAIN>` on the ECS task definition to lock the API to your domain (read from `DOMAIN` in `aws.env`).
