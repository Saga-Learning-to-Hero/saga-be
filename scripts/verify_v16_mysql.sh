#!/usr/bin/env bash
# Disposable, no-install local MySQL 8 verification for V16 (task.parent_external_id /
# task.parent_external_key). Never point this at Railway/Aiven production. See
# scripts/verify_v15_mysql.sh for the analogous flow and its notes on flyway-maven-plugin
# invocation / mvnw.cmd `&`-in-URL gotchas -- both apply here too.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33063
DB=saga_v16_verify
FLYWAY_VERSION=12.4.0 # must match flyway-core's resolved version (mvn dependency:tree -Dincludes=org.flywaydb)

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
    rm -rf "$WORKDIR"
}
trap cleanup EXIT

echo "== waiting for mysqld to accept connections =="
for i in $(seq 1 30); do
    "$MYSQL_HOME/bin/mysqladmin.exe" -uroot -h127.0.0.1 -P"$PORT" ping --silent 2>/dev/null && break
    sleep 1
done

MYSQL() { "$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" "$DB"; }
FLYWAY_ARGS=(-Dflyway.url="jdbc:mysql://127.0.0.1:$PORT/$DB" -Dflyway.user=root -Dflyway.password= \
    -Dflyway.locations=filesystem:src/main/resources/db/migration)

echo "== 0: create database =="
"$MYSQL_HOME/bin/mysql.exe" -uroot -h127.0.0.1 -P"$PORT" -e "CREATE DATABASE $DB;"

echo "== 1: migrate V1 -> V15 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=15

echo "== 2: seed pre-V16 baseline (project chain + existing Task rows, no parent columns yet) =="
MYSQL <<'SQL'
INSERT INTO semester (id, code, name) VALUES ('11111111-0000-0000-0000-000000000003','FA24','Fall 2024');
INSERT INTO subject (id, subject_code, name) VALUES ('11111111-0000-0000-0000-000000000001','SWP391','Software Project');
INSERT INTO academic_class (id, class_code, name, semester_id) VALUES ('11111111-0000-0000-0000-000000000002','SE1801','SE1801','11111111-0000-0000-0000-000000000003');
INSERT INTO course (id, subject_id, academic_class_id, semester_id, name)
VALUES ('11111111-0000-0000-0000-000000000004','11111111-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000003','Course A');

INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-00000000000a','11111111-0000-0000-0000-000000000004','Project A');

-- Existing rows from BEFORE V16 -- must survive untouched, with parent columns absent (until V16).
INSERT INTO task (id, project_id, external_id, external_key, title, issue_type_name)
VALUES ('e0000000-0000-0000-0000-00000000001a','a0000000-0000-0000-0000-00000000000a','10001','SAGA-1','Ordinary task','Task');
INSERT INTO task (id, project_id, external_id, external_key, title, issue_type_name)
VALUES ('e0000000-0000-0000-0000-00000000001b','a0000000-0000-0000-0000-00000000000a','10049','SAGA-49','Parent story','Story');
SQL

echo "== 3: apply V16 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== 4: existing rows retained after V16, parent columns NULL =="
MYSQL -e "SELECT id, external_id, external_key, parent_external_id, parent_external_key FROM task ORDER BY id;"

echo "== 5: SHOW CREATE TABLE (confirm nullable columns, no new FK/index) =="
MYSQL -e "SHOW CREATE TABLE task\G"

echo "== 6: insert a Subtask referencing the parent by external identity (no FK, parent row need not exist for THIS check but does here) =="
MYSQL <<'SQL'
INSERT INTO task (id, project_id, external_id, external_key, title, issue_type_name, parent_external_id, parent_external_key)
VALUES ('e0000000-0000-0000-0000-00000000001c','a0000000-0000-0000-0000-00000000000a','10050','SAGA-50','Implement login form','Subtask','10049','SAGA-49');
SELECT id, external_key, issue_type_name, parent_external_id, parent_external_key FROM task WHERE id='e0000000-0000-0000-0000-00000000001c';
SQL

echo "== 7: child-before-parent -- insert a Subtask whose parent external id has NO local Task row at all (must succeed, no FK) =="
MYSQL <<'SQL'
INSERT INTO task (id, project_id, external_id, external_key, title, issue_type_name, parent_external_id, parent_external_key)
VALUES ('e0000000-0000-0000-0000-00000000001d','a0000000-0000-0000-0000-00000000000a','10051','SAGA-51','Another subtask, parent not synced yet','Subtask','99999','SAGA-999');
SELECT id, external_key, parent_external_id, parent_external_key FROM task WHERE id='e0000000-0000-0000-0000-00000000001d';
SQL

echo "== 8: update parent value (simulates webhook parent-changed reconciliation) =="
MYSQL <<'SQL'
UPDATE task SET parent_external_id='10060', parent_external_key='SAGA-60' WHERE id='e0000000-0000-0000-0000-00000000001c';
SELECT id, parent_external_id, parent_external_key FROM task WHERE id='e0000000-0000-0000-0000-00000000001c';
SQL

echo "== flyway validate =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"

echo "== second migrate -> expect 0 pending migrations =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" \
    | grep -i "up to date\|no migration necessary"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
