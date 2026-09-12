#!/usr/bin/env bash
# Disposable, no-install local MySQL 8 verification for V14 (active-scoped Jira cloud/project
# uniqueness). Never point this at Railway/Aiven production. This exact flow was run and verified
# live against a downloaded mysql-8.0.40-winx64 no-install ZIP (no Docker required/available).
#
# Notable gotchas discovered while writing this (fixed below, do not reintroduce):
# - There is no <flyway-maven-plugin> declared in pom.xml (only flyway-core/flyway-mysql as
#   runtime deps), so `./mvnw.cmd flyway:migrate` resolves to nothing usable -- invoke the plugin
#   by its full groupId:artifactId:version instead, matching the flyway-core version in the pom.
# - mvnw.cmd is a Windows batch file; an unescaped `&` inside a -D value (e.g.
#   `...?allowPublicKeyRetrieval=true&useSSL=false`) gets parsed by cmd.exe as a command
#   separator and silently truncates/breaks the rest of the argument list. Do not put `&` in any
#   -D value passed to mvnw.cmd; MySQL 8.0.40 connects fine here without those params.
# - `project.course_id` is NOT NULL and `course` requires subject_id/academic_class_id/semester_id,
#   and `academic_class` carries its own semester_id with a composite FK back to course
#   (fk_course_class_semester) -- seed the full chain, not just `project`.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33061
DB=saga_v14_verify
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

echo "== 1: migrate V1 -> V13 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=13

echo "== 2: seed realistic pre-V14 baseline (each source distinct -- old blanket constraint still active) =="
MYSQL <<'SQL'
INSERT INTO semester (id, code, name) VALUES ('11111111-0000-0000-0000-000000000003','FA24','Fall 2024');
INSERT INTO subject (id, subject_code, name) VALUES ('11111111-0000-0000-0000-000000000001','SWP391','Software Project');
INSERT INTO academic_class (id, class_code, name, semester_id) VALUES ('11111111-0000-0000-0000-000000000002','SE1801','SE1801','11111111-0000-0000-0000-000000000003');
INSERT INTO course (id, subject_id, academic_class_id, semester_id, name)
VALUES ('11111111-0000-0000-0000-000000000004','11111111-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000003','Course A');

INSERT INTO project (id, course_id, name) VALUES
 ('a0000000-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000004','Project P1'),
 ('a0000000-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000004','Project P2'),
 ('a0000000-0000-0000-0000-000000000003','11111111-0000-0000-0000-000000000004','Project P3');

INSERT INTO jira_integration (id, project_id, cloud_id, jira_project_id, project_key, connection_status, consecutive_failures, version)
VALUES
 ('b0000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000001','cloud-alpha','PROJ-100','ALPHA','ACTIVE',0,0),
 ('b0000000-0000-0000-0000-000000000002','a0000000-0000-0000-0000-000000000002','cloud-beta','PROJ-200','BETA','REVOKED',0,0),
 ('b0000000-0000-0000-0000-000000000003','a0000000-0000-0000-0000-000000000003','cloud-gamma','PROJ-300','GAMMA','ACTIVE',0,0);
SQL

echo "== 3: apply V14 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== H: historical rows unchanged =="
MYSQL -e "SELECT id, cloud_id, jira_project_id, connection_status FROM jira_integration ORDER BY id;"

echo "== G: SHOW CREATE TABLE (confirm generated columns + new key + old key gone) =="
MYSQL -e "SHOW CREATE TABLE jira_integration\G"

echo "== A: multiple REVOKED rows, same source -> allowed =="
MYSQL <<'SQL'
INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-000000000004','11111111-0000-0000-0000-000000000004','Project P4');
INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-000000000005','11111111-0000-0000-0000-000000000004','Project P5');
INSERT INTO jira_integration (id, project_id, cloud_id, jira_project_id, project_key, connection_status, consecutive_failures, version)
VALUES ('b0000000-0000-0000-0000-000000000004','a0000000-0000-0000-0000-000000000004','cloud-shared','PROJ-SHARED','SH1','REVOKED',0,0);
INSERT INTO jira_integration (id, project_id, cloud_id, jira_project_id, project_key, connection_status, consecutive_failures, version)
VALUES ('b0000000-0000-0000-0000-000000000005','a0000000-0000-0000-0000-000000000005','cloud-shared','PROJ-SHARED','SH2','REVOKED',0,0);
SQL

echo "== B: one ACTIVE + multiple REVOKED same source -> allowed =="
MYSQL <<'SQL'
INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-000000000006','11111111-0000-0000-0000-000000000004','Project P6');
INSERT INTO jira_integration (id, project_id, cloud_id, jira_project_id, project_key, connection_status, consecutive_failures, version)
VALUES ('b0000000-0000-0000-0000-000000000006','a0000000-0000-0000-0000-000000000006','cloud-shared','PROJ-SHARED','SH3','ACTIVE',0,0);
SQL

echo "== C: second ACTIVE same source -> expect rejection by uk_jira_active_cloud_project =="
set +e
MYSQL <<'SQL'
INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-000000000007','11111111-0000-0000-0000-000000000004','Project P7');
INSERT INTO jira_integration (id, project_id, cloud_id, jira_project_id, project_key, connection_status, consecutive_failures, version)
VALUES ('b0000000-0000-0000-0000-000000000007','a0000000-0000-0000-0000-000000000007','cloud-shared','PROJ-SHARED','SH4','ACTIVE',0,0);
SQL
STATUS=$?
set -e
if [ "$STATUS" -ne 0 ]; then
    echo "  -> expected: rejected (check the error above names uk_jira_active_cloud_project)"
else
    echo "  -> UNEXPECTED: second ACTIVE insert on the same source SUCCEEDED (constraint not enforced!)"
    exit 1
fi

echo "== D: ACTIVE -> REVOKED makes generated columns NULL =="
MYSQL <<'SQL'
UPDATE jira_integration SET connection_status='REVOKED' WHERE id='b0000000-0000-0000-0000-000000000006';
SELECT id, connection_status, active_cloud_id, active_jira_project_id FROM jira_integration WHERE id='b0000000-0000-0000-0000-000000000006';
SQL

echo "== E: another historical row can now become ACTIVE =="
MYSQL <<'SQL'
INSERT INTO jira_integration (id, project_id, cloud_id, jira_project_id, project_key, connection_status, consecutive_failures, version)
VALUES ('b0000000-0000-0000-0000-000000000007','a0000000-0000-0000-0000-000000000007','cloud-shared','PROJ-SHARED','SH4','ACTIVE',0,0);
SELECT id, connection_status, active_cloud_id, active_jira_project_id FROM jira_integration WHERE cloud_id='cloud-shared' ORDER BY id;
SQL

echo "== F: unrelated Jira sources remain allowed =="
MYSQL <<'SQL'
INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-000000000008','11111111-0000-0000-0000-000000000004','Project P8');
INSERT INTO jira_integration (id, project_id, cloud_id, jira_project_id, project_key, connection_status, consecutive_failures, version)
VALUES ('b0000000-0000-0000-0000-000000000008','a0000000-0000-0000-0000-000000000008','cloud-delta','PROJ-400','DELTA','ACTIVE',0,0);
SQL

echo "== I: flyway validate =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"

echo "== J: migrate again -> expect 0 pending migrations =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" \
    | grep -i "up to date\|no migration necessary"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
