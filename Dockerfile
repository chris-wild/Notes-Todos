# Lambda container image for notes-todos.
# Runs the existing Express server unchanged via the AWS Lambda Web Adapter.
# Target architecture: arm64 (Graviton) — build with:
#   docker buildx build --platform linux/arm64 ...
#
# The LWA extension bridges the Lambda Runtime API to the local HTTP server,
# so backend/server.js needs no handler rewrite.

# ---- builder: install backend deps (argon2 + sharp are native) ----
FROM public.ecr.aws/docker/library/node:20-bookworm AS build
WORKDIR /app
# Build tooling in case a native prebuild is unavailable for this ABI.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
COPY backend/package*.json ./backend/
RUN cd backend && npm ci --omit=dev

# ---- runtime ----
FROM public.ecr.aws/docker/library/node:20-bookworm-slim
# AWS Lambda Web Adapter as a Lambda extension.
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:0.9.0 /lambda-adapter /opt/extensions/lambda-adapter
WORKDIR /app
ENV NODE_ENV=production \
    PORT=8080 \
    AWS_LWA_PORT=8080 \
    AWS_LWA_READINESS_CHECK_PATH=/healthz \
    AWS_LWA_INVOKE_MODE=buffered
COPY --from=build /app/backend/node_modules ./backend/node_modules
COPY backend ./backend
# The React build is also copied so the function can serve the whole app if
# needed; in production CloudFront serves static from S3 and only routes
# /api/* to this function.
COPY build ./build
CMD ["node", "backend/server.js"]
