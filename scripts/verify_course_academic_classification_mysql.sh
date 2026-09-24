#!/usr/bin/env bash
# Disposable, no-install MySQL 8.4 verification for the course-level Academic Classification read
# (AiAcademicClassificationRepository#findCoursePage content query + countQuery). Never point this
# script at a shared database: it initializes a temporary data directory and removes it.
#
# Optional: SAGA_MYSQL_HOME=<extracted mysql-8.4.x-winx64 dir> skips the ~250MB download.
set -euo pipefail

MYSQL_VERSION="8.4.4"
WORKDIR="$(mktemp -d)"
PORT="${SAGA_VERIFY_MYSQL_PORT:-33085}"
DB=saga_course_cls_verify
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
    --datadir="$DATADIR" --basedir="$MYSQL_HOME" --port="$PORT" --mysqlx=OFF \
    --pid-file="$WORKDIR/mysqld.pid" --log-error="$WORKDIR/mysqld.err" --bind-address=127.0.0.1 \
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
"$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" -e "SELECT VERSION(); CREATE DATABASE $DB;"
MYSQL() { "$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" "$DB" "$@"; }
FLYWAY_ARGS=(-Dflyway.url="jdbc:mysql://127.0.0.1:$PORT/$DB" -Dflyway.user=root -Dflyway.password= -Dflyway.locations=filesystem:src/main/resources/db/migration)

echo "== 1: migrate V1 -> V33 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"
echo "== 2: validate Flyway history =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"
COUNT="$(MYSQL -N -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL;")"
if [ "$COUNT" != "33" ]; then echo "FAIL: expected 33 versioned migrations, got $COUNT"; exit 1; fi

echo "== 3: real service + repository course query (content + countQuery) on MySQL =="
./mvnw.cmd test "-Dtest=com.saga.be.service.ai.LecturerCourseAcademicClassificationMysqlIT" "-Dsaga.verify.mysql=true" "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"
echo "== done: mysqld will be stopped and the disposable datadir removed =="
