#!/bin/sh
# Checks the egress: allowed hosts through the proxy, other hosts refused, no way around it.
set -eu
cd "$(dirname "$0")"
docker compose up -d --wait
run() { docker run --rm --network factory-sandboxes --cap-drop ALL curlimages/curl:latest -sS -o /dev/null -w '%{http_code}' --max-time 10 "$@" 2>/dev/null || true; }
allowed=$(run -x http://factory-egress:3128 https://registry.npmjs.org/)
refused=$(run -x http://factory-egress:3128 https://example.com/)
direct=$(run https://registry.npmjs.org/)
echo "allowed host through the proxy: $allowed, other host: $refused, without the proxy: $direct"
[ "$allowed" = 200 ] && [ "$refused" != 200 ] && [ "$direct" != 200 ] && echo OK
