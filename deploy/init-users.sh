#!/bin/bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=app_password="$(cat /run/secrets/app-db-password)" <<'SQL'
CREATE ROLE exobrain_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
GRANT CONNECT ON DATABASE exobrain TO exobrain_app;
GRANT USAGE ON SCHEMA public TO exobrain_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO exobrain_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO exobrain_app;
SQL
