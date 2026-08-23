#!/usr/bin/env bash
# Seed a demo tenant with users, instruments, and trades (including one that trips a breach).
# Run after `docker compose up`:  ./scripts/seed.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
ADMIN_KEY="${PLATFORM_ADMIN_KEY:-dev-platform-admin-key-change-me}"
TENANT_NAME="Acme Trading"

admin_post() { curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-Platform-Admin-Key: $ADMIN_KEY" -d "$2"; }
auth_post()  { curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -H "Authorization: Bearer $2" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "$RANDOM-$RANDOM")" -d "$3"; }
field()      { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }   # extract a quoted JSON field (portable)

echo "▶ Onboarding tenant '$TENANT_NAME'..."
TENANT_ID=$(admin_post "/admin/tenants" "{\"name\":\"$TENANT_NAME\",\"riskMode\":\"MONITOR\"}" | field id)
echo "  tenantId = $TENANT_ID"

echo "▶ Creating users (risk manager + trader)..."
admin_post "/admin/tenants/$TENANT_ID/users" '{"email":"rm@acme.com","password":"secret123","role":"RISK_MANAGER"}' >/dev/null
admin_post "/admin/tenants/$TENANT_ID/users" '{"email":"trader@acme.com","password":"secret123","role":"TRADER"}' >/dev/null

login() { curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
            -d "{\"tenantName\":\"$TENANT_NAME\",\"email\":\"$1\",\"password\":\"secret123\"}" | field token; }
RM_TOKEN=$(login rm@acme.com)
TRADER_TOKEN=$(login trader@acme.com)

echo "▶ Registering instruments..."
for sym in CRUDE-OIL NAT-GAS; do
  curl -s -X POST "$BASE/api/v1/instruments" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $RM_TOKEN" \
    -d "{\"symbol\":\"$sym\",\"positionLimit\":10000,\"markPrice\":72.5}" >/dev/null
done

echo "▶ Submitting trades (the second CRUDE-OIL buy pushes past the 10,000 limit → breach)..."
auth_post "/api/v1/trades" "$TRADER_TOKEN" '{"instrumentSymbol":"CRUDE-OIL","side":"BUY","quantity":5000,"price":72.55}' >/dev/null
auth_post "/api/v1/trades" "$TRADER_TOKEN" '{"instrumentSymbol":"CRUDE-OIL","side":"BUY","quantity":6000,"price":73.10}' >/dev/null
auth_post "/api/v1/trades" "$TRADER_TOKEN" '{"instrumentSymbol":"NAT-GAS","side":"SELL","quantity":2000,"price":3.15}' >/dev/null

cat <<EOF

✔ Seeded. Explore:
  Positions : curl -H "Authorization: Bearer $TRADER_TOKEN" $BASE/api/v1/positions
  Breaches  : curl -H "Authorization: Bearer $RM_TOKEN" $BASE/api/v1/risk/breaches
  Summary   : curl -H "Authorization: Bearer $TRADER_TOKEN" $BASE/api/v1/summary
  P&L run   : curl -X POST -H "Authorization: Bearer $RM_TOKEN" "$BASE/api/v1/reports/pnl/run?date=\$(date -u +%F)"   # needs an ADMIN token
EOF
