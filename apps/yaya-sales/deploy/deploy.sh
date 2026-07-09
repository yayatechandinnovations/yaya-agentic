#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  yaya-sales — deploy / bring-up script
# ============================================================
#
#  Self-contained Docker Compose stack: Postgres + backend. On first run it
#  generates all required secrets into deploy/.env (DB password, AES-256
#  master key, the per-tenant agent service-token secret, the sandbox PSP
#  webhook secret), then builds and starts the stack.
#
#  Usage:
#    ./deploy.sh                # generate secrets if needed, then up -d --build
#    ./deploy.sh up             # same as default
#    ./deploy.sh secrets        # only (re)generate missing secrets in .env
#    ./deploy.sh down           # stop the stack (keeps the DB volume)
#    ./deploy.sh destroy        # stop + remove the DB volume (DESTRUCTIVE)
#    ./deploy.sh logs           # tail logs
#    ./deploy.sh status         # compose ps + health + trust-wiring info
#    ./deploy.sh credentials    # print the trust-wiring values for the agent side
#
#  The operator cutover from the legacy in-core commerce (trust wiring, tool
#  import, data migration) is documented step by step in ../docs/RUNBOOK.md.
# ============================================================

DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DEPLOY_DIR"
ENV_FILE="$DEPLOY_DIR/.env"

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_B=$'\033[1;34m'; C_G=$'\033[1;32m'; C_R=$'\033[1;31m'; C_Y=$'\033[1;33m'
else
  C_RESET=""; C_B=""; C_G=""; C_R=""; C_Y=""
fi
log()  { echo "${C_B}==>${C_RESET} $*"; }
ok()   { echo "${C_G}✓${C_RESET}  $*"; }
warn() { echo "${C_Y}!${C_RESET}  $*"; }
die()  { echo "${C_R}✗${C_RESET}  $*" >&2; exit 1; }

command -v docker >/dev/null || die "docker is required."
command -v openssl >/dev/null || die "openssl is required (for secret generation)."

b64()  { openssl rand -base64 "$1" | tr -d '\n'; }

# Set KEY=VALUE in .env (append if absent, replace if present-but-empty).
set_env() {
  local key="$1" val="$2"
  if grep -qE "^${key}=" "$ENV_FILE" 2>/dev/null; then
    # only fill if currently empty
    if grep -qE "^${key}=$" "$ENV_FILE"; then
      # portable in-place edit
      local tmp; tmp="$(mktemp)"
      sed "s|^${key}=$|${key}=${val}|" "$ENV_FILE" > "$tmp" && mv "$tmp" "$ENV_FILE"
    fi
  else
    echo "${key}=${val}" >> "$ENV_FILE"
  fi
}

get_env() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2-; }

generate_secrets() {
  if [ ! -f "$ENV_FILE" ]; then
    log "Creating $ENV_FILE from template…"
    cp "$DEPLOY_DIR/.env.example" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
  fi
  log "Filling any missing secrets in .env…"
  set_env "SALES_DB_PASSWORD"                          "$(b64 24)"
  set_env "YAYA_SALES_SECRETS_ENCRYPTION_KEY"          "$(b64 32)"
  set_env "SALES_SERVICE_TOKEN_SECRET"                 "$(b64 32)"
  set_env "YAYA_SALES_PAYMENTS_SANDBOX_WEBHOOK_SECRET" "$(b64 24)"
  # Ensure the tenant id is present (from template default).
  grep -qE "^SALES_TENANT_ID=" "$ENV_FILE" || echo "SALES_TENANT_ID=default" >> "$ENV_FILE"
  ok "Secrets ready in $ENV_FILE (chmod 600, git-ignored)."
  if [ "$(get_env SALES_TENANT_ID)" = "default" ]; then
    warn "SALES_TENANT_ID is still 'default' — set your real tenant id before wiring the agent."
  fi
}

print_credentials() {
  local port tenant secret issuer
  port="$(get_env SALES_PORT)"; port="${port:-8092}"
  tenant="$(get_env SALES_TENANT_ID)"; tenant="${tenant:-default}"
  secret="$(get_env SALES_SERVICE_TOKEN_SECRET)"
  issuer="$(get_env YAYA_SALES_AUTH_ISSUER_URI)"
  cat <<EOF

${C_G}────────────────────────────────────────────────────────────${C_RESET}
  API:            http://localhost:${port}   (health: /actuator/health)
  Tool manifest:  GET /v1/sales/agent/manifest
  Tenant:         ${tenant}
  OIDC issuer:    ${issuer:-<unset — admin API locked, see .env>}

  Agent trust wiring — the SAME secret on both sides (RUNBOOK step 2):
    yaya-sales   yaya.sales.auth.service-secrets.${tenant}
    yaya-agentic yaya.agentic.auth.service-token.tenant-secrets.${tenant}
    secret:      ${secret:-<unset — run ./deploy.sh secrets>}

  The AES master key and PSP secrets live in ${ENV_FILE}
  (keep it safe, it's git-ignored). Full cutover: ../docs/RUNBOOK.md
${C_G}────────────────────────────────────────────────────────────${C_RESET}
EOF
}

case "${1:-up}" in
  secrets)
    generate_secrets
    ;;
  up|"")
    generate_secrets
    log "Building & starting the stack (docker compose up -d --build)…"
    docker compose up -d --build
    ok "Stack is up."
    print_credentials
    ;;
  down)
    docker compose down
    ok "Stopped (DB volume kept)."
    ;;
  destroy)
    docker compose down -v
    ok "Stopped and removed the DB volume."
    ;;
  logs)
    docker compose logs -f --tail=120
    ;;
  status)
    docker compose ps
    echo
    port="$(get_env SALES_PORT)"; port="${port:-8092}"
    if curl -fsS --max-time 5 "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
      ok "backend healthy (http://localhost:${port}/actuator/health)"
    else
      warn "backend not healthy yet."
    fi
    print_credentials
    ;;
  credentials|creds)
    print_credentials
    ;;
  *)
    die "unknown command '$1' (try: up | secrets | down | destroy | logs | status | credentials)"
    ;;
esac
