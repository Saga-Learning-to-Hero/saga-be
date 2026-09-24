#!/usr/bin/env bash
# Disposable MySQL 8.4 check for V35 retry lineage. Never use a shared database.
set -euo pipefail

MYSQL_VERSION="8.4.4"
WORKDIR="$(mktemp -d)"
PORT="${SAGA_VERIFY_MYSQL_PORT:-33073}"
DB=saga_v35_verify
FLYWAY_VERSION=12.4.0
if [ -n "${SAGA_MYSQL_HOME:-}" ]; then MYSQL_HOME="$SAGA_MYSQL_HOME"; else
  curl -fsSL -o "$WORKDIR/mysql.zip" "https://cdn.mysql.com/archives/mysql-8.4/mysql-$MYSQL_VERSION-winx64.zip"
  unzip -q "$WORKDIR/mysql.zip" -d "$WORKDIR"; MYSQL_HOME="$WORKDIR/mysql-$MYSQL_VERSION-winx64"
fi
DATADIR="$WORKDIR/data"; mkdir -p "$DATADIR"
"$MYSQL_HOME/bin/mysqld.exe" --initialize-insecure --datadir="$DATADIR" --basedir="$MYSQL_HOME"
"$MYSQL_HOME/bin/mysqld.exe" --datadir="$DATADIR" --basedir="$MYSQL_HOME" --port="$PORT" --bind-address=127.0.0.1 --mysqlx=OFF --pid-file="$WORKDIR/mysqld.pid" --log-error="$WORKDIR/mysqld.err" >/dev/null 2>&1 & MYSQLD_PID=$!
cleanup() { "$MYSQL_HOME/bin/mysqladmin.exe" -uroot -h127.0.0.1 -P"$PORT" shutdown >/dev/null 2>&1 || kill "$MYSQLD_PID" >/dev/null 2>&1 || true; rm -rf "$WORKDIR"; }
trap cleanup EXIT
for i in $(seq 1 30); do "$MYSQL_HOME/bin/mysqladmin.exe" -uroot -h127.0.0.1 -P"$PORT" ping --silent >/dev/null 2>&1 && break; sleep 1; done
"$MYSQL_HOME/bin/mysqladmin.exe" -uroot -h127.0.0.1 -P"$PORT" ping --silent
"$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" -e "CREATE DATABASE $DB;"
MYSQL() { "$MYSQL_HOME/bin/mysql.exe" -N -uroot -h127.0.0.1 -P"$PORT" "$DB" "$@"; }
MVN="${SAGA_MAVEN_COMMAND:-./mvnw.cmd}"
ARGS=(-Dflyway.url="jdbc:mysql://127.0.0.1:$PORT/$DB" -Dflyway.user=root -Dflyway.password= -Dflyway.locations=filesystem:src/main/resources/db/migration)
"$MVN" -q "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${ARGS[@]}" -Dflyway.target=34
RUN_ID=11111111-1111-1111-1111-111111111111
MYSQL -e "SET FOREIGN_KEY_CHECKS=0; INSERT INTO ai_analysis_run (id, artifact_type, artifact_id, artifact_revision, analysis_type, status, evidence_hash, policy_version, prompt_version, schema_version, provider_config_hash, idempotency_key) VALUES ('$RUN_ID', 'COMMIT', '$RUN_ID', 'revision', 'COMMIT_INTELLIGENCE', 'FAILED', REPEAT('a',64), 'policy', 'prompt', 'schema', REPEAT('b',64), REPEAT('c',64));"
"$MVN" -q "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${ARGS[@]}"
"$MVN" -q "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${ARGS[@]}"
[ "$(MYSQL -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL;")" = "35" ]
[ "$(MYSQL -e "SELECT canonical_identity_key = idempotency_key AND retry_attempt = 0 FROM ai_analysis_run WHERE id='$RUN_ID';")" = "1" ]
MYSQL -e "INSERT INTO ai_analysis_run (id, artifact_type, artifact_id, artifact_revision, analysis_type, status, evidence_hash, policy_version, prompt_version, schema_version, provider_config_hash, idempotency_key, canonical_identity_key, retry_attempt) VALUES ('22222222-2222-2222-2222-222222222222', 'COMMIT', '$RUN_ID', 'revision', 'COMMIT_INTELLIGENCE', 'QUEUED', REPEAT('d',64), 'policy', 'prompt', 'schema', REPEAT('e',64), REPEAT('f',64), REPEAT('c',64), 1);"
if MYSQL -e "INSERT INTO ai_analysis_run (id, artifact_type, artifact_id, artifact_revision, analysis_type, status, evidence_hash, policy_version, prompt_version, schema_version, provider_config_hash, idempotency_key, canonical_identity_key, retry_attempt) VALUES ('33333333-3333-3333-3333-333333333333', 'COMMIT', '$RUN_ID', 'revision', 'COMMIT_INTELLIGENCE', 'QUEUED', REPEAT('d',64), 'policy', 'prompt', 'schema', REPEAT('e',64), REPEAT('1',64), REPEAT('c',64), 1);" >/dev/null 2>&1; then exit 1; fi
"$MVN" test "-Dtest=com.saga.be.repository.V35MysqlHibernateValidateIT" "-Dsaga.verify.mysql=true" "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"
