#!/usr/bin/env bash
# Disposable, no-install local MySQL 8.0.40 verification for V22
# (native SAGA parent_task_id). Never point this at Railway/Aiven production.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33067
DB=saga_v22_verify
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

echo "== 1: migrate V1 -> V21 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=21

echo "== 2: flyway info before V22 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:info" "${FLYWAY_ARGS[@]}" -Dflyway.target=21

echo "== 3: apply V22 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== 4: flyway validate (22 migrations) =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"
COUNT="$(MYSQL -N -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL;")"
if [ "$COUNT" != "22" ]; then
    echo "FAIL: expected 22 versioned migrations, got $COUNT"
    exit 1
fi

echo "== 5: SHOW CREATE TABLE task (FK + index) =="
CREATE_TASK="$(MYSQL -N -e "SHOW CREATE TABLE task;")"
echo "$CREATE_TASK"
echo "$CREATE_TASK" | grep -q 'parent_task_id' || { echo "FAIL: parent_task_id missing"; exit 1; }
echo "$CREATE_TASK" | grep -q 'ix_task_parent_task_id' || { echo "FAIL: index missing"; exit 1; }
echo "$CREATE_TASK" | grep -q 'fk_task_parent_task' || { echo "FAIL: FK missing"; exit 1; }

echo "== 6: TaskHierarchyV22MysqlIT =="
./mvnw.cmd test "-Dtest=com.saga.be.repository.TaskHierarchyV22MysqlIT" "-Dsaga.verify.mysql=true" \
    "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
