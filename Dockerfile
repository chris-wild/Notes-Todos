# syntax=docker/dockerfile:1

# -------------------------
# Build frontend
# -------------------------
FROM node:22-alpine AS web-build
WORKDIR /app

COPY package.json package-lock.json ./
# NOTE: lockfile in this repo may be out-of-sync with react-scripts' TypeScript peer range.
# For deterministic builds you should fix the lockfile; for now we use npm install.
RUN npm install --no-audit --no-fund

COPY public ./public
COPY src ./src
# Optional build-time API URL (normally you will use same-origin /api behind ALB)
ARG REACT_APP_API_URL
ENV REACT_APP_API_URL=$REACT_APP_API_URL
RUN npm run build

# -------------------------
# Build backend
# -------------------------
FROM node:22-alpine AS api-build
WORKDIR /app/backend

COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev

COPY backend ./

# -------------------------
# Runtime image
# -------------------------
FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production

# Backend
COPY --from=api-build /app/backend ./backend

# Frontend build served by backend from ../build
COPY --from=web-build /app/build ./build

EXPOSE 3001

# Required at runtime:
# - DATABASE_URL
# Optional:
# - PGSSL=true (for AWS RDS)
# - JWT_SECRET
# - ENCRYPTION_KEY (for API key encryption at rest)
# - CORS_ORIGIN (comma-separated)
# - PORT
CMD ["node", "backend/server.js"]
