#!/usr/bin/env bash
# Runs AmlIntegrationTest against a throwaway local PostgreSQL.
#
# It starts a private Postgres on port 55432 in a temp folder, boots the app
# with placeholder settings (no real bank, mail, Redis or Firebase is
# contacted), runs the test, then deletes everything it created.
# It never touches your normal databases.
#
# Usage:  PG_BIN="/c/Program Files/PostgreSQL/18/bin" scripts/run-aml-integration-tests.sh
set -euo pipefail

PG_BIN="${PG_BIN:-/c/Program Files/PostgreSQL/18/bin}"
PORT="${AML_IT_PG_PORT:-55432}"
WORK="$(mktemp -d)"
trap '"$PG_BIN/pg_ctl" -D "$WORK/pg" -m fast stop >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

"$PG_BIN/initdb" -D "$WORK/pg" -U amlit -A trust -E UTF8 --locale=C >/dev/null
"$PG_BIN/pg_ctl" -D "$WORK/pg" -o "-p $PORT" -l "$WORK/pg.log" -w start >/dev/null
"$PG_BIN/psql" -h localhost -p "$PORT" -U amlit -d postgres -q -c "CREATE DATABASE amlit;"

# A fake Firebase service account, so the app can start. It is never used.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/key.pem" 2>/dev/null
python - "$WORK" <<'EOF'
import json, os, sys
d = sys.argv[1]
json.dump({"type": "service_account", "project_id": "aml-it", "private_key_id": "x",
           "private_key": open(os.path.join(d, "key.pem")).read(),
           "client_email": "aml-it@aml-it.iam.gserviceaccount.com", "client_id": "1",
           "token_uri": "https://oauth2.googleapis.com/token"},
          open(os.path.join(d, "firebase.json"), "w"))
EOF

export AML_IT=true
export FIREBASE_SERVICE_ACCOUNT_FILE="$WORK/firebase.json"
# The production migrations expect tables that already exist, so Hibernate
# builds the schema here. V64 itself is checked separately against Postgres.
export SPRING_FLYWAY_ENABLED=false
export DATABASE_URL="jdbc:postgresql://localhost:$PORT/amlit" DATABASE_USERNAME=amlit DATABASE_PASSWORD=x
export GOOGLE_AUTHORIZATION_URI=http://x GOOGLE_CLIENT_ID=x GOOGLE_CLIENT_SECRET=x GOOGLE_JWK_SET_URI=http://x
export GOOGLE_REDIRECT_URI=http://x GOOGLE_SCOPE=email GOOGLE_TOKEN_URI=http://x GOOGLE_USER_INFO_URI=http://x
export GOOGLE_USER_NAME_ATTRIBUTE=sub JWT_EXPIRATION=3600000
export JWT_SECRET="$(openssl rand -base64 48)"
export MAIL_FROM=aml-it@example.test MAIL_HOST=localhost MAIL_PORT=2525 MAIL_USERNAME=x MAIL_PASSWORD=x
export SECUREWAVE_BASE_URL=http://x SECUREWAVE_BUSINESS_ID=x SECUREWAVE_PUBLIC_KEY=x SECUREWAVE_SECRET_KEY=x
export TWILIO_ACCOUNT_SID=ACx TWILIO_AUTH_TOKEN=x TWILIO_PHONE_NUMBER=+10000000000
export RUBIES_API_KEY=not-a-real-key RUBIES_BASE_URL=http://localhost:9

cd "$(dirname "$0")/.."
./mvnw -q -o test -Dtest=AmlIntegrationTest,AdminPushIntegrationTest,AdminBroadcastJobIntegrationTest,AdminBudgetCancelIntegrationTest "$@"
