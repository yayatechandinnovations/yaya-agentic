#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  import-legacy-commerce.sh — one-shot per tenant (design §12.2)
# ============================================================
#
#  Drives the yaya-sales admin API to import the CSVs produced by
#  export-legacy-commerce.sql:
#
#    1. create (or reuse) the store for the tenant
#    2. create (or reuse) a sellable location
#    3. create (or reuse) the catalog
#    4. bulk-upsert products/variants (CSV bulk endpoint — re-runnable:
#       rows keyed by title+sku update in place)
#    5. publish the catalog
#    6. opening-balance seed: one inventory ADJUSTMENT per SKU with reason
#       "opening balance (legacy import)" so the ledger starts clean and
#       on-hand matches legacy reality on day one (skipped per SKU if the
#       ledger already has that movement — safe to re-run)
#
#  Usage:
#    OPERATOR_TOKEN=… TENANT_ID=acme CURRENCY=DOP ./import-legacy-commerce.sh
#
#  Environment / flags:
#    SALES_API        yaya-sales base URL          (default http://localhost:8092)
#    OPERATOR_TOKEN   Bearer JWT from the OIDC issuer yaya-sales trusts  (required)
#    TENANT_ID        tenant the store belongs to                        (required)
#    CURRENCY         store default currency, e.g. DOP / USD             (required)
#    STORE_NAME       store display name           (default "Main Store")
#    CATALOG_NAME     catalog display name         (default "Legacy import")
#    LOCATION_LABEL   inventory location label     (default "Main")
#    EXPORT_DIR       dir holding legacy_products.csv + legacy_opening_balance.csv (default .)
#    --dry-run | DRY_RUN=1   print every step without writing anything
# ============================================================

DRY_RUN="${DRY_RUN:-0}"
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '3,36p' "$0"; exit 0 ;;
    *) echo "unknown flag: $arg (try --help)" >&2; exit 2 ;;
  esac
done

SALES_API="${SALES_API:-http://localhost:8092}"
STORE_NAME="${STORE_NAME:-Main Store}"
CATALOG_NAME="${CATALOG_NAME:-Legacy import}"
LOCATION_LABEL="${LOCATION_LABEL:-Main}"
EXPORT_DIR="${EXPORT_DIR:-.}"
OB_REASON="opening balance (legacy import)"

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_B=$'\033[1;34m'; C_G=$'\033[1;32m'; C_R=$'\033[1;31m'; C_Y=$'\033[1;33m'
else
  C_RESET=""; C_B=""; C_G=""; C_R=""; C_Y=""
fi
log()  { echo "${C_B}==>${C_RESET} $*"; }
ok()   { echo "${C_G}✓${C_RESET}  $*"; }
warn() { echo "${C_Y}!${C_RESET}  $*"; }
die()  { echo "${C_R}✗${C_RESET}  $*" >&2; exit 1; }

command -v curl >/dev/null || die "curl is required."
command -v jq   >/dev/null || die "jq is required."
[ -n "${OPERATOR_TOKEN:-}" ] || die "OPERATOR_TOKEN is required (Bearer JWT for the yaya-sales admin API)."
[ -n "${TENANT_ID:-}" ]      || die "TENANT_ID is required."
[ -n "${CURRENCY:-}" ]       || die "CURRENCY is required (store default currency, e.g. DOP)."

PRODUCTS_CSV="$EXPORT_DIR/legacy_products.csv"
BALANCE_CSV="$EXPORT_DIR/legacy_opening_balance.csv"
[ -f "$PRODUCTS_CSV" ] || die "$PRODUCTS_CSV not found — run export-legacy-commerce.sql first (see header)."
[ -f "$BALANCE_CSV" ]  || warn "$BALANCE_CSV not found — the opening-balance seed will be skipped."

[ "$DRY_RUN" = "1" ] && warn "DRY RUN — nothing will be written."

# api METHOD PATH [curl-extra-args…] — fails loudly with the response body.
api() {
  local method="$1" path="$2"; shift 2
  local out code body
  out="$(curl -sS -X "$method" "$SALES_API$path" \
              -H "Authorization: Bearer $OPERATOR_TOKEN" \
              "$@" -w $'\n%{http_code}')" \
    || die "$method $path — curl failed (is yaya-sales up at $SALES_API?)"
  code="${out##*$'\n'}"
  body="${out%$'\n'*}"
  case "$code" in
    2*) printf '%s' "$body" ;;
    *)  die "$method $path → HTTP $code: $body" ;;
  esac
}

curl -fsS --max-time 5 "$SALES_API/actuator/health" >/dev/null \
  || die "yaya-sales is not healthy at $SALES_API/actuator/health"
ok "yaya-sales reachable at $SALES_API"

# ---- 1. store --------------------------------------------------------------
log "Store '$STORE_NAME' (tenant $TENANT_ID, $CURRENCY)…"
STORE_ID="$(api GET "/v1/sales/admin/stores?tenantId=$TENANT_ID" \
  | jq -r --arg n "$STORE_NAME" '[.[] | select(.displayName == $n)][0].id.value // empty')"
if [ -n "$STORE_ID" ]; then
  ok "reusing store $STORE_ID"
elif [ "$DRY_RUN" = "1" ]; then
  warn "would create store '$STORE_NAME'"; STORE_ID="(dry-run)"
else
  STORE_ID="$(jq -n --arg t "$TENANT_ID" --arg n "$STORE_NAME" --arg c "$CURRENCY" \
        '{tenantId: $t, displayName: $n, defaultCurrency: $c}' \
    | api POST "/v1/sales/admin/stores" -H "Content-Type: application/json" -d @- \
    | jq -r '.id.value')"
  ok "created store $STORE_ID"
fi
STORE_HDR=(-H "X-Yaya-Store-Id: $STORE_ID")

# ---- 2. location -----------------------------------------------------------
log "Location '$LOCATION_LABEL'…"
if [ "$DRY_RUN" = "1" ] && [ "$STORE_ID" = "(dry-run)" ]; then
  warn "would create location '$LOCATION_LABEL' (WAREHOUSE, sellable)"; LOCATION_ID="(dry-run)"
else
  LOCATION_ID="$(api GET "/v1/sales/admin/locations" "${STORE_HDR[@]}" \
    | jq -r --arg l "$LOCATION_LABEL" '[.[] | select(.label == $l)][0].id.value // empty')"
  if [ -n "$LOCATION_ID" ]; then
    ok "reusing location $LOCATION_ID"
  elif [ "$DRY_RUN" = "1" ]; then
    warn "would create location '$LOCATION_LABEL' (WAREHOUSE, sellable)"; LOCATION_ID="(dry-run)"
  else
    LOCATION_ID="$(jq -n --arg l "$LOCATION_LABEL" '{label: $l, kind: "WAREHOUSE", sellable: true}' \
      | api POST "/v1/sales/admin/locations" "${STORE_HDR[@]}" -H "Content-Type: application/json" -d @- \
      | jq -r '.id.value')"
    ok "created location $LOCATION_ID"
  fi
fi

# ---- 3. catalog ------------------------------------------------------------
log "Catalog '$CATALOG_NAME'…"
if [ "$DRY_RUN" = "1" ] && [ "$STORE_ID" = "(dry-run)" ]; then
  warn "would create catalog '$CATALOG_NAME'"; CATALOG_ID="(dry-run)"; CATALOG_STATUS="DRAFT"
else
  CATALOG_JSON="$(api GET "/v1/sales/admin/catalogs" "${STORE_HDR[@]}" \
    | jq --arg n "$CATALOG_NAME" '[.[] | select(.displayName == $n)][0] // empty')"
  if [ -n "$CATALOG_JSON" ]; then
    CATALOG_ID="$(jq -r '.id.value' <<<"$CATALOG_JSON")"
    CATALOG_STATUS="$(jq -r '.status' <<<"$CATALOG_JSON")"
    ok "reusing catalog $CATALOG_ID ($CATALOG_STATUS)"
  elif [ "$DRY_RUN" = "1" ]; then
    warn "would create catalog '$CATALOG_NAME'"; CATALOG_ID="(dry-run)"; CATALOG_STATUS="DRAFT"
  else
    CATALOG_ID="$(jq -n --arg n "$CATALOG_NAME" '{displayName: $n}' \
      | api POST "/v1/sales/admin/catalogs" "${STORE_HDR[@]}" -H "Content-Type: application/json" -d @- \
      | jq -r '.id.value')"
    CATALOG_STATUS="DRAFT"
    ok "created catalog $CATALOG_ID"
  fi
fi

# ---- 4. products (CSV bulk upsert) ------------------------------------------
ROWS=$(( $(wc -l < "$PRODUCTS_CSV") - 1 ))
log "Importing $ROWS product/variant rows from $PRODUCTS_CSV…"
if [ "$DRY_RUN" = "1" ]; then
  warn "would POST /v1/sales/admin/catalogs/$CATALOG_ID/products/bulk"
else
  RESULT="$(api POST "/v1/sales/admin/catalogs/$CATALOG_ID/products/bulk" "${STORE_HDR[@]}" \
    -H "Content-Type: text/csv" --data-binary "@$PRODUCTS_CSV")"
  CREATED="$(jq -r '.created' <<<"$RESULT")"
  UPDATED="$(jq -r '.updated' <<<"$RESULT")"
  ERRORS="$(jq -r '.errors | length' <<<"$RESULT")"
  ok "products: $CREATED created, $UPDATED updated, $ERRORS row errors"
  if [ "$ERRORS" != "0" ]; then
    jq -r '.errors[] | "  row \(.row): \(.message)"' <<<"$RESULT" >&2
    die "bulk import reported $ERRORS row errors — fix the CSV and re-run (upsert is safe)."
  fi
fi

# ---- 5. publish ------------------------------------------------------------
if [ "$CATALOG_STATUS" = "PUBLISHED" ]; then
  ok "catalog already published"
elif [ "$DRY_RUN" = "1" ]; then
  warn "would publish catalog $CATALOG_ID"
else
  api POST "/v1/sales/admin/catalogs/$CATALOG_ID/publish" "${STORE_HDR[@]}" >/dev/null
  ok "catalog published"
fi

# ---- 6. opening-balance seed (design §12.2) ---------------------------------
if [ ! -f "$BALANCE_CSV" ]; then
  warn "no $BALANCE_CSV — skipping the opening-balance seed."
else
  log "Opening-balance seed from $BALANCE_CSV (reason: \"$OB_REASON\")…"
  SEEDED=0; SKIPPED=0
  # header: sku,on_hand — SKUs are ids (no embedded commas); strip CSV quoting.
  while IFS=, read -r sku on_hand; do
    sku="${sku%\"}"; sku="${sku#\"}"
    on_hand="$(echo "$on_hand" | tr -d '[:space:]"')"
    [ -n "$sku" ] && [ -n "$on_hand" ] || continue
    if [ "$DRY_RUN" = "1" ]; then
      warn "would adjust $sku by +$on_hand at $LOCATION_LABEL"
      continue
    fi
    # Idempotency: skip if this SKU's ledger already has the opening-balance
    # movement (a re-run must never double stock).
    ALREADY="$(api GET "/v1/sales/admin/inventory/$sku/ledger?location=$LOCATION_ID" "${STORE_HDR[@]}" \
      | jq -r --arg r "$OB_REASON" '[.[] | select(.reason == $r)] | length')"
    if [ "$ALREADY" != "0" ]; then
      SKIPPED=$((SKIPPED + 1))
      continue
    fi
    jq -n --arg loc "$LOCATION_ID" --argjson d "$on_hand" --arg r "$OB_REASON" \
          '{location: $loc, delta: $d, reason: $r}' \
      | api POST "/v1/sales/admin/inventory/$sku/adjust" "${STORE_HDR[@]}" \
            -H "Content-Type: application/json" -d @- >/dev/null
    SEEDED=$((SEEDED + 1))
    ok "seeded $sku → on-hand $on_hand"
  done < <(tail -n +2 "$BALANCE_CSV")
  [ "$DRY_RUN" = "1" ] || ok "opening balances: $SEEDED seeded, $SKIPPED already present (skipped)"
fi

echo
ok "Import complete."
cat <<EOF
   store:    $STORE_ID   ('$STORE_NAME', tenant $TENANT_ID)
   location: $LOCATION_ID   ('$LOCATION_LABEL')
   catalog:  $CATALOG_ID   ('$CATALOG_NAME')

Next (docs/RUNBOOK.md step 6 verification):
  * product count:  curl -s -H "Authorization: Bearer \$OPERATOR_TOKEN" -H "X-Yaya-Store-Id: $STORE_ID" \\
        $SALES_API/v1/sales/admin/catalogs/$CATALOG_ID/products | jq length
  * on-hand:        curl -s -H "Authorization: Bearer \$OPERATOR_TOKEN" -H "X-Yaya-Store-Id: $STORE_ID" \\
        "$SALES_API/v1/sales/admin/inventory" | jq
  * point the profile's commerce binding at storeId "$STORE_ID" (RUNBOOK step 5).
EOF
