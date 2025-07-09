#!/usr/bin/env bash
set -euo pipefail

# ────── 1) CSP LOGIN & PASSWORD CHANGE ────────────────────────────────────────

COOKIE_JAR=$(mktemp)
URL="http://iris:52773/csp/sys/%25CSP.Portal.Home.zen"
NEW_PASSWORD="sys"

# Extract the CSP session token from the cookie jar
get_token() {
  grep IRISSessionToken "$COOKIE_JAR" | tail -1 | awk '{print $7}'
}

# 1a) Fetch the login page (to get initial token)
curl -s -c "$COOKIE_JAR" "$URL" >/dev/null

# 1b) Log in as _SYSTEM/SYS
curl -s -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "IRISSessionToken=$(get_token)" \
  --data-urlencode "IRISUsername=_SYSTEM" \
  --data-urlencode "IRISPassword=SYS" \
  --data-urlencode "IRISLogin=Login" \
  "$URL" >/dev/null

# 1c) Change password to “sys”
curl -s -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "IRISSessionToken=$(get_token)" \
  --data-urlencode "IRISUsername=_SYSTEM" \
  --data-urlencode "IRISOldPassword=SYS" \
  --data-urlencode "IRISPassword=$NEW_PASSWORD" \
  --data-urlencode "IRISLogin=Login" \
  "$URL" >/dev/null

echo "Attempting password change for _SYSTEM → '$NEW_PASSWORD'…"

# 1d) Verify new login works
HTTP_CODE=$(
  curl -s -b "$COOKIE_JAR" -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "IRISSessionToken=$(get_token)" \
    --data-urlencode "IRISUsername=_SYSTEM" \
    --data-urlencode "IRISPassword=$NEW_PASSWORD" \
    --data-urlencode "IRISLogin=Login" \
    -w "%{http_code}" -o /dev/null \
    "$URL"
)

rm "$COOKIE_JAR"

if [[ "$HTTP_CODE" != "302" ]]; then
  echo "✖ HTTP login with new password failed (code $HTTP_CODE)"
  exit 1
fi
echo "✔ Password change confirmed. Will use: _SYSTEM/$NEW_PASSWORD"