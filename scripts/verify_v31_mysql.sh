#!/usr/bin/env bash
# Disposable, no-install local MySQL 8.0.40 verification for V31
# (ai_analysis_adjudication.updated_at, the column V30 forgot). Never point this at production.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33069
DB=saga_v31_verify
FLYWAY_VERSION=12.4.0

echo "== downloading MySQL $MYSQL_VERSION no-install ZIP (~250MB) =="
curl -sL -o "$WORKDIR/mysql.zip" "https://cdn.mysql.com//archives/mysql-8.0/mysql-$MYSQL_VERSION-winx64.zip"
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

MYSQL() { "$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" "$DB" "$@"; }
FLYWAY_ARGS=(-Dflyway.url="jdbc:mysql://127.0.0.1:$PORT/$DB" -Dflyway.user=root -Dflyway.password= \
    -Dflyway.locations=filesystem:src/main/resources/db/migration)

echo "== 0: create database =="
"$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" -e "CREATE DATABASE $DB;"

echo "== 1: migrate V1 -> V31 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== 2: flyway validate (31 migrations) =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"
COUNT="$(MYSQL -N -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL;")"
if [ "$COUNT" != "31" ]; then
    echo "FAIL: expected 31 versioned migrations, got $COUNT"
    exit 1
fi

echo "== 3: ai_analysis_adjudication has updated_at =="
CREATE_ADJ="$(MYSQL -N -e "SHOW CREATE TABLE ai_analysis_adjudication;")"
echo "$CREATE_ADJ"
echo "$CREATE_ADJ" | grep -q 'updated_at' || { echo "FAIL: updated_at still missing"; exit 1; }

echo "== 4: V31MysqlHibernateValidateIT (full entity-vs-schema validate) =="
./mvnw.cmd test "-Dtest=com.saga.be.repository.V31MysqlHibernateValidateIT" "-Dsaga.verify.mysql=true" \
    "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
