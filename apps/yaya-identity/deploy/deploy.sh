#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  yaya-identity — deploy / bring-up script
# ============================================================
#
#  Self-contained Docker Compose stack: Postgres + backend + console
#  (nginx, same-origin). On first run it generates all required secrets
#  into deploy/.env (including a first-access "master user"), then builds
#  and starts the stack.
#
#  Usage:
#    ./deploy.sh                # generate secrets if needed, then up -d --build
#    ./deploy.sh up             # same as default
#    ./deploy.sh secrets        # only (re)generate missing secrets in .env
#    ./deploy.sh down           # stop the stack (keeps the DB volume)
#    ./deploy.sh destroy        # stop + remove the DB volume (DESTRUCTIVE)
#    ./deploy.sh logs           # tail logs
#    ./deploy.sh status         # compose ps + health + access info
#    ./deploy.sh credentials    # print the console URL + master-user login
#
#  Override the public origin (e.g. behind a real domain):
#    PUBLIC_ORIGIN=https://identity.example.com ./deploy.sh
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
# A URL/shell-safe password (no +,/,= that could trip up shells or URLs).
pw()   { openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 20; }

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
  set_env "IDENTITY_DB_PASSWORD"                     "$(b64 24)"
  set_env "YAYA_IDENTITY_SECRETS_ENCRYPTION_KEY"     "$(openssl rand -base64 32 | tr -d '\n')"
  set_env "YAYA_IDENTITY_BOOTSTRAP_SERVICE_CLIENT_SECRET" "$(b64 24)"
  set_env "YAYA_IDENTITY_BOOTSTRAP_OWNER_PASSWORD"   "$(pw)"
  # Ensure the owner email + origin are present (from template defaults).
  grep -qE "^YAYA_IDENTITY_BOOTSTRAP_OWNER_EMAIL=" "$ENV_FILE" || echo "YAYA_IDENTITY_BOOTSTRAP_OWNER_EMAIL=owner@example.com" >> "$ENV_FILE"
  grep -qE "^PUBLIC_ORIGIN=" "$ENV_FILE" || echo "PUBLIC_ORIGIN=${PUBLIC_ORIGIN:-http://localhost:8091}" >> "$ENV_FILE"
  ok "Secrets ready in $ENV_FILE (chmod 600, git-ignored)."
}

print_credentials() {
  local origin email pass
  origin="$(get_env PUBLIC_ORIGIN)"; origin="${origin:-http://localhost:8091}"
  email="$(get_env YAYA_IDENTITY_BOOTSTRAP_OWNER_EMAIL)"
  pass="$(get_env YAYA_IDENTITY_BOOTSTRAP_OWNER_PASSWORD)"
  cat <<EOF

${C_G}────────────────────────────────────────────────────────────${C_RESET}
  Console:   ${origin}
  Sign in with the first-access master user (owner):
     email:    ${email}
     password: ${pass}
  Change/rotate it after first login. The service-client secret and
  the AES master key live in ${ENV_FILE} (keep it safe, it's git-ignored).
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
    warn "First boot builds the Flutter console — allow ~1–2 min, then reload."
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
    local_origin="$(get_env PUBLIC_ORIGIN)"; local_origin="${local_origin:-http://localhost:8091}"
    if curl -fsS --max-time 5 "http://localhost:8090/oauth2/jwks" >/dev/null 2>&1; then
      ok "backend JWKS reachable (http://localhost:8090/oauth2/jwks)"
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
