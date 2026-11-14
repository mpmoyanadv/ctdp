#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:4200}"
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
DEMO_PASSWORD="${DEMO_PASSWORD:-Password123!}"

echo "Running smoke checks against BASE_URL=${BASE_URL} (BACKEND_URL=${BACKEND_URL})"

check_json_field() {
  local json_payload="$1"
  local python_expr="$2"
  JSON_PAYLOAD="${json_payload}" python3 -c "
import json, os, sys
data = json.loads(os.environ['JSON_PAYLOAD'])
if not (${python_expr}):
    sys.exit(1)
"
}

login_and_get_token() {
  local email="$1"
  local resp
  resp="$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${DEMO_PASSWORD}\"}")"
  JSON_PAYLOAD="${resp}" python3 -c "
import json, os, sys
data = json.loads(os.environ['JSON_PAYLOAD'])
token = data.get('accessToken')
if not token:
    sys.exit(1)
print(token)
"
}

echo "1. Checking backend health endpoint..."
HEALTH_RESP="$(curl -fsS "${BACKEND_URL}/actuator/health")"
check_json_field "${HEALTH_RESP}" "data.get('status') == 'UP'"
echo "   OK: backend health is UP"

if [[ "${BASE_URL}" != "${BACKEND_URL}" ]]; then
  echo "2. Checking frontend root..."
  HTTP_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/")"
  if [[ "${HTTP_CODE}" != "200" ]]; then
    echo "   FAIL: expected HTTP 200 from ${BASE_URL}/, got ${HTTP_CODE}"
    exit 1
  fi
  echo "   OK: frontend root returned HTTP 200"
fi

echo "3. Logging in as customer@capi.test and checking transactions + paginated disputes..."
CUST_TOKEN="$(login_and_get_token "customer@capi.test")"
TX_RESP="$(curl -fsS "${BASE_URL}/api/v1/transactions" -H "Authorization: Bearer ${CUST_TOKEN}")"
check_json_field "${TX_RESP}" "isinstance(data, list) and len(data) > 0"
DISPUTES_RESP="$(curl -fsS "${BASE_URL}/api/v1/disputes?page=0&size=20" -H "Authorization: Bearer ${CUST_TOKEN}")"
check_json_field "${DISPUTES_RESP}" "isinstance(data.get('content'), list) and 'totalElements' in data"
echo "   OK: customer transactions and paginated disputes verified"

echo "4. Logging in as staff@capi.test and checking staff queue..."
STAFF_TOKEN="$(login_and_get_token "staff@capi.test")"
STAFF_DISPUTES="$(curl -fsS "${BASE_URL}/api/v1/disputes?page=0&size=20" -H "Authorization: Bearer ${STAFF_TOKEN}")"
check_json_field "${STAFF_DISPUTES}" "isinstance(data.get('content'), list)"
echo "   OK: staff dispute queue verified"

echo "5. Logging in as admin@capi.test and checking paginated audit logs + CSV export..."
ADMIN_TOKEN="$(login_and_get_token "admin@capi.test")"
AUDIT_RESP="$(curl -fsS "${BASE_URL}/api/v1/admin/audit-logs?page=0&size=10" -H "Authorization: Bearer ${ADMIN_TOKEN}")"
check_json_field "${AUDIT_RESP}" "isinstance(data.get('content'), list) and len(data['content']) > 0"
CSV_CODE="$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api/v1/admin/audit-logs/export" -H "Authorization: Bearer ${ADMIN_TOKEN}")"
if [[ "${CSV_CODE}" != "200" ]]; then
  echo "   FAIL: expected HTTP 200 from audit CSV export, got ${CSV_CODE}"
  exit 1
fi
echo "   OK: admin audit logs and CSV export verified"

echo "All smoke checks passed."

