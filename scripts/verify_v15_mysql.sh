#!/usr/bin/env bash
# Disposable, no-install local MySQL 8 verification for V15 (active-scoped GitHub
# git_repo uniqueness). Never point this at Railway/Aiven production. This exact flow was
# run and verified live against a downloaded mysql-8.0.40-winx64 no-install ZIP (no Docker
# required/available). See scripts/verify_v14_mysql.sh for the analogous Jira verification and
# its notes on flyway-maven-plugin invocation / mvnw.cmd `&`-in-URL gotchas -- both apply here too.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33062
DB=saga_v15_verify
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

echo "== 1: migrate V1 -> V14 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=14

echo "== 2: seed pre-V15 baseline -- Project A: repo R REVOKED + historical commit; Project B: no repo yet =="
MYSQL <<'SQL'
INSERT INTO semester (id, code, name) VALUES ('11111111-0000-0000-0000-000000000003','FA24','Fall 2024');
INSERT INTO subject (id, subject_code, name) VALUES ('11111111-0000-0000-0000-000000000001','SWP391','Software Project');
INSERT INTO academic_class (id, class_code, name, semester_id) VALUES ('11111111-0000-0000-0000-000000000002','SE1801','SE1801','11111111-0000-0000-0000-000000000003');
INSERT INTO course (id, subject_id, academic_class_id, semester_id, name)
VALUES ('11111111-0000-0000-0000-000000000004','11111111-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000003','Course A');

INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-00000000000a','11111111-0000-0000-0000-000000000004','Project A');
INSERT INTO git_repo (id, project_id, provider, repository_id, full_name, connection_status, consecutive_failures, version)
VALUES ('c0000000-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000a','GITHUB',555000111,'saga/repo-r','REVOKED',0,0);
INSERT INTO git_commit (id, repo_id, sha_hash, message)
VALUES ('d0000000-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-00000000000a','deadbeefcafefeed0001','Historical commit under Project A');

INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-00000000000b','11111111-0000-0000-0000-000000000004','Project B');
INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-00000000000c','11111111-0000-0000-0000-000000000004','Project C');
SQL

echo "== 3: apply V15 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== A: historical REVOKED row + commit unchanged =="
MYSQL -e "SELECT id, project_id, connection_status FROM git_repo ORDER BY id;"
MYSQL -e "SELECT id, repo_id, sha_hash FROM git_commit ORDER BY id;"

echo "== G: SHOW CREATE TABLE (confirm generated columns + new keys + old key gone) =="
MYSQL -e "SHOW CREATE TABLE git_repo\G"

echo "== B claims ACTIVE R and stores its own commit projection for the identical SHA =="
MYSQL <<'SQL'
INSERT INTO git_repo (id, project_id, provider, repository_id, full_name, connection_status, consecutive_failures, version)
VALUES ('c0000000-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-00000000000b','GITHUB',555000111,'saga/repo-r','ACTIVE',0,0);
INSERT INTO git_commit (id, repo_id, sha_hash, message)
VALUES ('d0000000-0000-0000-0000-00000000000b','c0000000-0000-0000-0000-00000000000b','deadbeefcafefeed0001','Same physical commit, Bs own projection');
SQL

echo "== second ACTIVE R for Project C -> expect rejection by uk_git_repo_active_provider_repository =="
set +e
MYSQL <<'SQL'
INSERT INTO git_repo (id, project_id, provider, repository_id, full_name, connection_status, consecutive_failures, version)
VALUES ('c0000000-0000-0000-0000-00000000000c','a0000000-0000-0000-0000-00000000000c','GITHUB',555000111,'saga/repo-r','ACTIVE',0,0);
SQL
STATUS=$?
set -e
if [ "$STATUS" -ne 0 ]; then
    echo "  -> expected: rejected (check the error above names uk_git_repo_active_provider_repository)"
else
    echo "  -> UNEXPECTED: second ACTIVE insert on the same repository SUCCEEDED (constraint not enforced!)"
    exit 1
fi

echo "== multiple REVOKED historical rows on the same repository are allowed =="
MYSQL <<'SQL'
INSERT INTO git_repo (id, project_id, provider, repository_id, full_name, connection_status, consecutive_failures, version)
VALUES ('c0000000-0000-0000-0000-00000000000c','a0000000-0000-0000-0000-00000000000c','GITHUB',555000111,'saga/repo-r','REVOKED',0,0);
SELECT id, project_id, connection_status FROM git_repo WHERE repository_id=555000111 ORDER BY id;
SQL

echo "== same-project duplicate for the same repository -> expect rejection by uk_git_repo_project_provider_repository =="
set +e
MYSQL <<'SQL'
INSERT INTO git_repo (id, project_id, provider, repository_id, full_name, connection_status, consecutive_failures, version)
VALUES ('c0000000-0000-0000-0000-00000000000d','a0000000-0000-0000-0000-00000000000a','GITHUB',555000111,'saga/repo-r-dup','REVOKED',0,0);
SQL
STATUS=$?
set -e
if [ "$STATUS" -ne 0 ]; then
    echo "  -> expected: rejected (check the error above names uk_git_repo_project_provider_repository)"
else
    echo "  -> UNEXPECTED: same-project duplicate SUCCEEDED (constraint not enforced!)"
    exit 1
fi

echo "== flyway validate =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"

echo "== second migrate -> expect 0 pending migrations =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" \
    | grep -i "up to date\|no migration necessary"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
