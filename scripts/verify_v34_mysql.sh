#!/usr/bin/env bash
# Disposable, no-install MySQL 8.4 verification for the V34 course multi-provider AI migration.
# Never point this script at a shared database: it initializes a temporary data directory and
# removes it. Set SAGA_MYSQL_HOME to an already-extracted mysql-8.4.x-winx64 directory to skip
# the ~250MB download.
set -euo pipefail

MYSQL_VERSION="8.4.4"
WORKDIR="$(mktemp -d)"
PORT="${SAGA_VERIFY_MYSQL_PORT:-33072}"
DB=saga_v34_verify
FLYWAY_VERSION=12.4.0

if [ -n "${SAGA_MYSQL_HOME:-}" ]; then
    MYSQL_HOME="$SAGA_MYSQL_HOME"
else
    echo "== downloading MySQL $MYSQL_VERSION no-install ZIP (~250MB) =="
    curl -sL -o "$WORKDIR/mysql.zip" "https://cdn.mysql.com/archives/mysql-8.4/mysql-$MYSQL_VERSION-winx64.zip"
    unzip -q "$WORKDIR/mysql.zip" -d "$WORKDIR"
    MYSQL_HOME="$WORKDIR/mysql-$MYSQL_VERSION-winx64"
fi
DATADIR="$WORKDIR/data"
mkdir -p "$DATADIR"

echo "== initializing disposable datadir (root, no password) =="
"$MYSQL_HOME/bin/mysqld.exe" --initialize-insecure --datadir="$DATADIR" --basedir="$MYSQL_HOME"
echo "== starting mysqld on 127.0.0.1:$PORT =="
"$MYSQL_HOME/bin/mysqld.exe" \
    --datadir="$DATADIR" --basedir="$MYSQL_HOME" --port="$PORT" \
    --socket="$WORKDIR/mysql.sock" --pid-file="$WORKDIR/mysqld.pid" \
    --log-error="$WORKDIR/mysqld.err" --bind-address=127.0.0.1 --mysqlx=OFF \
    > "$WORKDIR/mysqld.stdout.log" 2>&1 &
MYSQLD_PID=$!

cleanup() {
    "$MYSQL_HOME/bin/mysqladmin.exe" -uroot -h127.0.0.1 -P"$PORT" shutdown >/dev/null 2>&1 || kill "$MYSQLD_PID" >/dev/null 2>&1 || true
    sleep 2
    rm -rf "$WORKDIR" || true
}
trap cleanup EXIT

echo "== waiting for mysqld to accept connections =="
for i in $(seq 1 30); do
    "$MYSQL_HOME/bin/mysqladmin.exe" -uroot -h127.0.0.1 -P"$PORT" ping --silent 2>/dev/null && break
    sleep 1
done
"$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" -e "CREATE DATABASE $DB;"
MYSQL() { "$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" "$DB" "$@"; }
FLYWAY_ARGS=(-Dflyway.url="jdbc:mysql://127.0.0.1:$PORT/$DB" -Dflyway.user=root -Dflyway.password= -Dflyway.locations=filesystem:src/main/resources/db/migration)
expect() { # expect <description> <expected> <sql>
    local actual
    actual="$(MYSQL -N -e "$3")"
    if [ "$actual" != "$2" ]; then echo "FAIL: $1 (expected '$2', got '$actual')"; exit 1; fi
    echo "ok: $1"
}
expect_error() { # expect_error <description> <sql>
    if MYSQL -e "SET FOREIGN_KEY_CHECKS=0; $2" >/dev/null 2>&1; then echo "FAIL: $1 was accepted"; exit 1; fi
    echo "ok: $1 rejected"
}

echo "== 1: migrate V1 -> V33 and seed a legacy (pre-V34) credential =="
./mvnw.cmd -q "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=33
COURSE_ID=11111111-1111-1111-1111-111111111111
LEGACY_ID=22222222-2222-2222-2222-222222222222
# Synthetic rows only (FK checks off for the fixture): the legacy client stored lowercase free text.
MYSQL -e "SET FOREIGN_KEY_CHECKS=0; INSERT INTO ai_course_provider_credential (id, course_id, provider_role, provider, encrypted_secret, encryption_nonce, encryption_key_version, fingerprint, last_four, status) VALUES ('$LEGACY_ID', '$COURSE_ID', 'PRIMARY', 'openai', 'ciphertext', 'nonce', 1, REPEAT('a', 64), 'abcd', 'ACTIVE');"

echo "== 2: migrate V33 -> V34 and validate Flyway history =="
./mvnw.cmd -q "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"
./mvnw.cmd -q "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"
expect "34 versioned migrations applied" 34 "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL;"

echo "== 3: data + DDL invariants =="
expect "legacy credential normalized to OPENAI" OPENAI "SELECT provider FROM ai_course_provider_credential WHERE id = '$LEGACY_ID';"
expect "old (course, role) unique key dropped" 0 "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$DB' AND table_name='ai_course_provider_credential' AND index_name='uk_ai_course_provider_credential_role';"
expect "new (course, role, provider) unique key" 3 "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$DB' AND table_name='ai_course_provider_credential' AND index_name='uk_ai_course_provider_credential_role_provider' AND non_unique = 0;"
MYSQL -e "SET FOREIGN_KEY_CHECKS=0; INSERT INTO ai_course_provider_credential (id, course_id, provider_role, provider, encrypted_secret, encryption_nonce, encryption_key_version, fingerprint, last_four, status) VALUES (UUID(), '$COURSE_ID', 'PRIMARY', 'GEMINI', 'c', 'n', 1, REPEAT('b', 64), 'efgh', 'UNVERIFIED'), (UUID(), '$COURSE_ID', 'PRIMARY', 'OPENROUTER', 'c', 'n', 1, REPEAT('c', 64), 'ijkl', 'UNVERIFIED');"
expect "three providers coexist for one (course, role)" 3 "SELECT COUNT(*) FROM ai_course_provider_credential WHERE course_id = '$COURSE_ID' AND provider_role = 'PRIMARY';"
expect_error "duplicate (course, role, provider)" "INSERT INTO ai_course_provider_credential (id, course_id, provider_role, provider, encrypted_secret, encryption_nonce, encryption_key_version, fingerprint, last_four, status) VALUES (UUID(), '$COURSE_ID', 'PRIMARY', 'GEMINI', 'c', 'n', 1, REPEAT('d', 64), 'mnop', 'UNVERIFIED');"
expect_error "arbitrary credential provider" "INSERT INTO ai_course_provider_credential (id, course_id, provider_role, provider, encrypted_secret, encryption_nonce, encryption_key_version, fingerprint, last_four, status) VALUES (UUID(), '$COURSE_ID', 'SECONDARY', 'anthropic', 'c', 'n', 1, REPEAT('e', 64), 'qrst', 'UNVERIFIED');"
MYSQL -e "SET FOREIGN_KEY_CHECKS=0; INSERT INTO ai_course_settings (id, course_id, automation_enabled, allow_platform_fallback) VALUES (UUID(), '$COURSE_ID', 1, 0);"
expect "existing/new settings default to legacy bindings and fallback OFF" "0	NULL	NULL" "SELECT fallback_enabled, primary_provider, secondary_model_id FROM ai_course_settings WHERE course_id = '$COURSE_ID';"
expect_error "provider without model" "UPDATE ai_course_settings SET primary_provider = 'GEMINI' WHERE course_id = '$COURSE_ID';"
expect_error "arbitrary binding provider" "UPDATE ai_course_settings SET primary_provider = 'MISTRAL', primary_model_id = 'x' WHERE course_id = '$COURSE_ID';"
MYSQL -e "UPDATE ai_course_settings SET primary_provider = 'GEMINI', primary_model_id = 'gemini-3.8-flash', fallback_enabled = 1 WHERE course_id = '$COURSE_ID';"
MYSQL -e "SET FOREIGN_KEY_CHECKS=0; INSERT INTO ai_course_fallback_binding (id, course_id, attempt_order, provider, model_id) VALUES (UUID(), '$COURSE_ID', 1, 'OPENROUTER', 'openrouter/free');"
expect_error "duplicate fallback binding" "INSERT INTO ai_course_fallback_binding (id, course_id, attempt_order, provider, model_id) VALUES (UUID(), '$COURSE_ID', 2, 'OPENROUTER', 'openrouter/free');"
expect_error "fallback attempt order beyond 3" "INSERT INTO ai_course_fallback_binding (id, course_id, attempt_order, provider, model_id) VALUES (UUID(), '$COURSE_ID', 4, 'OPENAI', 'gpt-5.6-sol');"
expect "decision provenance columns" "ai_provider:varchar(32) fallback_attempts_json:mediumtext" "SELECT GROUP_CONCAT(CONCAT(column_name, ':', column_type) ORDER BY column_name SEPARATOR ' ') FROM information_schema.columns WHERE table_schema='$DB' AND table_name='ai_analysis_provider_decision' AND column_name IN ('ai_provider','fallback_attempts_json');"

echo "== 4: Hibernate ddl-auto=validate through V34 =="
./mvnw.cmd test "-Dtest=com.saga.be.repository.V34MysqlHibernateValidateIT" "-Dsaga.verify.mysql=true" "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"
echo "== done: mysqld will be stopped and the disposable datadir removed =="
