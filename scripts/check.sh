#!/usr/bin/env bash
#
# Unified check entry point (task 08). GitHub Actions calls this file; scripts/check.ps1
# is the Windows equivalent and runs the same steps with the same switches.
#
# Usage: scripts/check.sh [--unit] [--postgres-it] [--frontend] [--build] [--all]
#                         [--require-database] [--help]
#
#   --unit             default deterministic backend unit profile:   jchatmind/mvnw -Punit test
#   --postgres-it      real PostgreSQL 17 + pgvector integration:    jchatmind/mvnw -Ppostgres-it test
#                      (reads EXOBRAIN_IT_JDBC_URL / EXOBRAIN_IT_USERNAME / EXOBRAIN_IT_PASSWORD;
#                      without them the integration checks report as skipped, never as passed)
#   --frontend         front-end lint:                               ui: npm run lint
#   --build            both front-end builds (lint runs only with --frontend):
#                        guest   VITE_PUBLIC_DEMO=true  /api/demo  /sse/demo  --base=/       --outDir=dist-public
#                        account VITE_PUBLIC_DEMO=false /admin/api /admin/sse --base=/admin/ --outDir=dist-admin
#   --all              --unit --postgres-it --frontend --build
#   --require-database turn a missing or unreachable test database into a failure (CI uses this)
#
# No switch means --unit. Every switch maps to a command that really runs: no -DskipTests,
# no ignored failures and no dynamic exclusions. A missing optional environment (no test
# database) is reported as skipped with its reason. No credential value is ever printed.
#
# Exit code 0 only when every selected step passed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BACKEND_DIR="${ROOT_DIR}/jchatmind"
UI_DIR="${ROOT_DIR}/ui"

usage() {
    sed -n '3,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

run_unit=0
run_it=0
run_lint=0
run_build=0
require_database=0
selected=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --unit) run_unit=1; selected=1 ;;
        --postgres-it) run_it=1; selected=1 ;;
        --frontend) run_lint=1; selected=1 ;;
        --build) run_build=1; selected=1 ;;
        --all) run_unit=1; run_it=1; run_lint=1; run_build=1; selected=1 ;;
        --require-database) require_database=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

if [ "${selected}" -eq 0 ]; then
    run_unit=1
    echo "info: no step selected, running the default unit profile (--unit); use --all for every step"
fi

FAILURES=0
SUMMARY=()

error() {
    echo "error: $*" >&2
}

fail_hard() {
    error "$*"
    exit 1
}

# ---------------------------------------------------------------- environment

if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
fi

require_java_21() {
    command -v java >/dev/null 2>&1 || fail_hard "java is not on PATH; install JDK 21 and set JAVA_HOME"
    local version
    version="$(java -version 2>&1 | head -n 1)"
    case "${version}" in
        *'"21.'*|*'version 21'*) ;;
        *) fail_hard "JDK 21 is required (pom java.version=21) but java reports: ${version}" ;;
    esac
    echo "java: ${version}"
}

require_node() {
    command -v npm >/dev/null 2>&1 || fail_hard "npm is not on PATH; install Node.js 22 or newer"
    echo "node: $(node --version) / npm: $(npm --version)"
}

maven() {
    # The repository wrapper is the only Maven used here; CI relies on its executable bit.
    if [ -x "${BACKEND_DIR}/mvnw" ]; then
        ( cd "${BACKEND_DIR}" && ./mvnw "$@" )
    else
        ( cd "${BACKEND_DIR}" && bash ./mvnw "$@" )
    fi
}

npm_ui() {
    ( cd "${UI_DIR}" && npm "$@" )
}

ensure_ui_dependencies() {
    if [ -d "${UI_DIR}/node_modules" ]; then
        echo "ui/node_modules exists; not running npm ci (CI installs it explicitly)"
    else
        echo "ui/node_modules is missing; running npm ci"
        npm_ui ci
    fi
}

if [ "${run_unit}" -eq 1 ] || [ "${run_it}" -eq 1 ]; then
    require_java_21
fi
if [ "${run_lint}" -eq 1 ] || [ "${run_build}" -eq 1 ]; then
    require_node
fi
if [ "${require_database}" -eq 1 ] && [ "${run_it}" -eq 1 ] && [ -z "${EXOBRAIN_IT_JDBC_URL:-}" ]; then
    fail_hard "--require-database was given but EXOBRAIN_IT_JDBC_URL is not set"
fi

# ---------------------------------------------------------------- steps

step_unit() {
    maven -Punit test
}

step_postgres_it() {
    if [ -n "${EXOBRAIN_IT_JDBC_URL:-}" ]; then
        echo "EXOBRAIN_IT_JDBC_URL=${EXOBRAIN_IT_JDBC_URL}"
        echo "EXOBRAIN_IT_USERNAME=${EXOBRAIN_IT_USERNAME:-<unset>}"
        if [ -n "${EXOBRAIN_IT_PASSWORD:-}" ]; then
            echo "EXOBRAIN_IT_PASSWORD: set (value not printed)"
        else
            echo "EXOBRAIN_IT_PASSWORD: unset"
        fi
    else
        echo "EXOBRAIN_IT_JDBC_URL is not set: the integration class reports every check as skipped"
    fi
    if [ "${require_database}" -eq 1 ]; then
        export EXOBRAIN_IT_REQUIRE_DATABASE=true
        echo "EXOBRAIN_IT_REQUIRE_DATABASE=true (a missing database fails this step)"
    fi
    maven -Ppostgres-it test
}

step_frontend_lint() {
    ensure_ui_dependencies || return 1
    npm_ui run lint || return 1
    npm_ui run test:state
}

step_frontend_build() {
    ensure_ui_dependencies
    echo "guest build: VITE_PUBLIC_DEMO=true VITE_API_BASE_URL=/api/demo VITE_SSE_BASE_URL=/sse/demo --base=/ --outDir=dist-public"
    ( cd "${UI_DIR}" && VITE_PUBLIC_DEMO=true VITE_API_BASE_URL=/api/demo VITE_SSE_BASE_URL=/sse/demo \
        npm run build -- --base=/ --outDir=dist-public ) || return 1
    if [ ! -f "${UI_DIR}/dist-public/index.html" ]; then
        error "guest build did not produce ui/dist-public/index.html"
        return 1
    fi

    echo "account build: VITE_PUBLIC_DEMO=false VITE_API_BASE_URL=/admin/api VITE_SSE_BASE_URL=/admin/sse --base=/admin/ --outDir=dist-admin"
    ( cd "${UI_DIR}" && VITE_PUBLIC_DEMO=false VITE_API_BASE_URL=/admin/api VITE_SSE_BASE_URL=/admin/sse \
        npm run build -- --base=/admin/ --outDir=dist-admin ) || return 1
    if [ ! -f "${UI_DIR}/dist-admin/index.html" ]; then
        error "account build did not produce ui/dist-admin/index.html"
        return 1
    fi

    echo "build outputs:"
    du -sh "${UI_DIR}/dist-public" "${UI_DIR}/dist-admin" 2>/dev/null || true
}

run_step() {
    local name="$1"
    shift
    echo
    echo "==> ${name}"
    if "$@"; then
        SUMMARY+=("PASS  ${name}")
    else
        SUMMARY+=("FAIL  ${name}")
        FAILURES=$((FAILURES + 1))
    fi
}

if [ "${run_unit}" -eq 1 ]; then run_step "backend unit profile (-Punit)" step_unit; fi
if [ "${run_it}" -eq 1 ]; then run_step "real PostgreSQL integration (-Ppostgres-it)" step_postgres_it; fi
if [ "${run_lint}" -eq 1 ]; then run_step "frontend lint (npm run lint)" step_frontend_lint; fi
if [ "${run_build}" -eq 1 ]; then run_step "frontend guest + account builds" step_frontend_build; fi

echo
echo "==================== check summary ===================="
for line in "${SUMMARY[@]}"; do
    printf '%s  %s\n' "${line%%  *}" "${line#*  }"
done
echo "exit: ${FAILURES} failing step(s) of ${#SUMMARY[@]}"
if [ "${FAILURES}" -gt 0 ]; then
    exit 1
fi
exit 0
