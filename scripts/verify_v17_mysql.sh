#!/usr/bin/env bash
# Disposable, no-install local MySQL 8 verification for V17 (task.start_date).
# Never point this at Railway/Aiven production. See scripts/verify_v16_mysql.sh
# for the analogous flow and its notes on flyway-maven-plugin invocation /
# mvnw.cmd `&`-in-URL gotchas -- both apply here too.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33064
DB=saga_v17_verify
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

echo "== 1: migrate V1 -> V16 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=16

echo "== 2: seed pre-V17 baseline (project chain + existing Task rows, no start_date yet) =="
MYSQL <<'SQL'
INSERT INTO semester (id, code, name) VALUES ('11111111-0000-0000-0000-000000000003','FA24','Fall 2024');
INSERT INTO subject (id, subject_code, name) VALUES ('11111111-0000-0000-0000-000000000001','SWP391','Software Project');
INSERT INTO academic_class (id, class_code, name, semester_id) VALUES ('11111111-0000-0000-0000-000000000002','SE1801','SE1801','11111111-0000-0000-0000-000000000003');
INSERT INTO course (id, subject_id, academic_class_id, semester_id, name)
VALUES ('11111111-0000-0000-0000-000000000004','11111111-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000003','Course A');

INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-00000000000a','11111111-0000-0000-0000-000000000004','Project A');

INSERT INTO task (id, project_id, external_id, external_key, title, issue_type_name, due_date)
VALUES ('e0000000-0000-0000-0000-00000000001a','a0000000-0000-0000-0000-00000000000a','10001','SAGA-1','Ordinary task','Task','2026-09-18 00:00:00.000000');
SQL

echo "== 3: apply V17 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== 4: existing rows retained after V17, start_date NULL, due_date preserved =="
MYSQL -e "SELECT id, external_key, due_date, start_date FROM task ORDER BY id;"

echo "== 5: SHOW CREATE TABLE (confirm start_date datetime(6) NULL, no start_date index) =="
CREATE_SQL="$(MYSQL -N -e "SHOW CREATE TABLE task;")"
echo "$CREATE_SQL"
echo "$CREATE_SQL" | grep -qi '`start_date` datetime(6)' || { echo "FAIL: start_date datetime(6) missing"; exit 1; }
echo "$CREATE_SQL" | grep -qi 'ix_task_start_date' && { echo "FAIL: ix_task_start_date present"; exit 1; } || true

echo "== 6: update start_date to local midnight and read it back =="
MYSQL <<'SQL'
UPDATE task SET start_date='2026-09-14 00:00:00.000000' WHERE id='e0000000-0000-0000-0000-00000000001a';
SELECT id, DATE(start_date) AS start_date_only, start_date, DATE(due_date) AS due_date_only, due_date
FROM task WHERE id='e0000000-0000-0000-0000-00000000001a';
SQL

echo "== 7: confirm no ix_task_start_date =="
MYSQL -e "SHOW INDEX FROM task WHERE Key_name LIKE '%start_date%';"

echo "== flyway validate =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"

echo "== second migrate -> expect 0 pending migrations =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" \
    | grep -i "up to date\|no migration necessary\|Successfully applied 0"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
