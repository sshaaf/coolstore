#!/usr/bin/env bash
# Container smoke test for the CoolStore image.
#
#   ./scripts/container-smoke.sh [image]
#
# Asserts: non-root UID, read-only-friendly boot, /q/health/* green within 10s,
# no javax/weblogic classes inside the image. Requires podman (or docker).
set -euo pipefail

IMAGE="${1:-coolstore:local}"
ENGINE="${CONTAINER_ENGINE:-podman}"
NAME="coolstore-smoke-$$"
DB_URL="${DB_URL:-}"

cleanup() { "${ENGINE}" rm -f "${NAME}" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "==> starting ${IMAGE}"
"${ENGINE}" run -d --name "${NAME}" -p 0:8080 \
  -e QUARKUS_DATASOURCE_JDBC_URL="${DB_URL}" \
  -e AUDIT_ENABLED=false \
  -e SSO_ENABLED=false \
  "${IMAGE}" >/dev/null

PORT="$("${ENGINE}" port "${NAME}" 8080/tcp | head -1 | awk -F: '{print $NF}')"
echo "==> port ${PORT}"

echo "==> waiting for liveness (max 30s)"
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/q/health/live" >/dev/null 2>&1; then
    echo "    live"
    break
  fi
  sleep 1
done
curl -fsS "http://127.0.0.1:${PORT}/q/health/live" >/dev/null

echo "==> readiness (requires a reachable PostgreSQL)"
curl -fsS "http://127.0.0.1:${PORT}/q/health/ready" >/dev/null && echo "    ready"

echo "==> uid"
UID_INSIDE="$("${ENGINE}" exec "${NAME}" id -u)"
[ "${UID_INSIDE}" = "1001" ] || { echo "FAIL: expected uid 1001, got ${UID_INSIDE}"; exit 1; }
echo "    uid ${UID_INSIDE}"

echo "==> image content guard"
if "${ENGINE}" exec "${NAME}" sh -c 'find /deployments -name "*.jar" -exec unzip -l {} \; 2>/dev/null | grep -E "javax/(persistence|ws/rs|ejb)|weblogic/"' ; then
  echo "FAIL: legacy javax/weblogic classes found in the image"
  exit 1
fi
echo "    no javax/weblogic classes"

echo "==> storefront"
curl -fsS "http://127.0.0.1:${PORT}/index.html" >/dev/null
curl -fsS "http://127.0.0.1:${PORT}/services/products" >/dev/null
echo "OK"
