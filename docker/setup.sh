#!/bin/sh

ADMIN_EMAIL=${MB_ADMIN_EMAIL:-admin@metabase.local}
ADMIN_PASSWORD=${MB_ADMIN_PASSWORD:-Metapass123}
METABASE_HOST=${MB_HOSTNAME:-metabase}
METABASE_PORT=${MB_PORT:-3000}

echo "Creating admin user…"

# 1) Grab the setup-token from session/properties
SETUP_TOKEN=$( \
  curl -s -m 5 \
    -H "Content-Type: application/json" \
    http://${METABASE_HOST}:${METABASE_PORT}/api/session/properties \
  | sed -n 's/.*"setup-token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
)

# 2) Call /api/setup, capture the full response
RESPONSE=$( \
  curl -s -X POST \
    -H "Content-Type: application/json" \
    http://${METABASE_HOST}:${METABASE_PORT}/api/setup \
    -d '{
      "token": "'"${SETUP_TOKEN}"'",
      "user": {
        "email": "'"${ADMIN_EMAIL}"'",
        "first_name": "Sidd",
        "last_name": "InterSystems",
        "password": "'"${ADMIN_PASSWORD}"'"
      },
      "prefs": {
        "allow_tracking": false,
        "site_name": "Metawhat"
      }
    }' \
)

# 3) Extract the returned session ID from the JSON
MB_TOKEN=$(printf '%s' "$RESPONSE" \
  | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([^,}]*\).*/\1/p' \
)

echo "✔️ Admin created, session ID = $MB_TOKEN"