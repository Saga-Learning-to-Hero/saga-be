#!/usr/bin/env bash
# Disposable, no-install MySQL 8.4 verification for the V32/V33 BYOK migrations. Never point
# this script at a shared database: it initializes a temporary data directory and removes it.
set -euo pipefail

MYSQL_VERSION="8.4.4"
WORKDIR="$(mktemp -d)"
PORT=33071
DB=saga_v33_verify
FLYWAY_VERSION=12.4.0

echo "== downloading MySQL $MYSQL_VERSION no-install ZIP (~250MB) =="
curl -sL -o "$WORKDIR/mysql.zip" "https://cdn.mysql.com/archives/mysql-8.4/mysql-$MYSQL_VERSION-winx64.zip"
unzip -q "$WORKDIR/mysql.zip" -d "$WORKDIR"
MYSQL_HOME="$WORKDIR/mysql-$MYSQL_VERSION-winx64"
DATADIR="$WORKDIR/data"
mkdir -p "$DATADIR"

echo "== initializing disposable datadir (root, no password) =="
"$MYSQL_HOME/bin/mysqld.exe" --initialize-insecure --datadir="$DATADIR" --basedir="$MYSQL_HOME"
echo "== starting mysqld on 127.0.0.1:$PORT =="
"$MYSQL_HOME/bin/mysqld.exe" \
    --datadir="$DATADIR" --basedir="$MYSQL_HOME" --port="$PORT" \
    --socket="$WORKDIR/mysql.sock" --pid-file="$WORKDIR/mysqld.pid" \
    --log-error="$WORKDIR/mysqld.err" --bind-address=127.0.0.1 \
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

echo "== 1: migrate V1 -> V33 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"
echo "== 2: validate Flyway history =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"
COUNT="$(MYSQL -N -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL;")"
if [ "$COUNT" != "33" ]; then echo "FAIL: expected 33 versioned migrations, got $COUNT"; exit 1; fi

echo "== 3: verify BYOK DDL invariants =="
MYSQL -N -e "SHOW CREATE TABLE ai_course_settings; SHOW CREATE TABLE ai_course_provider_credential; SHOW CREATE TABLE ai_analysis_provider_decision;"
for table in ai_course_settings ai_course_provider_credential; do
    MYSQL -N -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$table' AND column_name IN ('created_at','updated_at');" | grep -qx 2
done
MYSQL -N -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$DB' AND table_name='ai_course_provider_credential' AND index_name='uk_ai_course_provider_credential_role';" | grep -qx 2
MYSQL -N -e "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='$DB' AND table_name='ai_analysis_provider_decision' AND constraint_name='fk_ai_provider_decision_course_credential';" | grep -qx 1

echo "== 4: Hibernate ddl-auto=validate through V33 =="
./mvnw.cmd test "-Dtest=com.saga.be.repository.V33MysqlHibernateValidateIT" "-Dsaga.verify.mysql=true" "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"
echo "== done: mysqld will be stopped and the disposable datadir removed =="
