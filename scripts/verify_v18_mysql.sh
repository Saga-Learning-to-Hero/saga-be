#!/usr/bin/env bash
# Disposable, no-install local MySQL 8.0.40 verification for V18
# (git_commit_branch + git_repo.branch_membership_synced_at).
# Never point this at Railway/Aiven production.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33065
DB=saga_v18_verify
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

echo "== 1: migrate V1 -> V17 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=17

echo "== 2: seed GitRepo + GitCommit (pre-V18, no membership table) =="
MYSQL <<'SQL'
INSERT INTO semester (id, code, name) VALUES ('11111111-0000-0000-0000-000000000003','FA24','Fall 2024');
INSERT INTO subject (id, subject_code, name) VALUES ('11111111-0000-0000-0000-000000000001','SWP391','Software Project');
INSERT INTO academic_class (id, class_code, name, semester_id) VALUES ('11111111-0000-0000-0000-000000000002','SE1801','SE1801','11111111-0000-0000-0000-000000000003');
INSERT INTO course (id, subject_id, academic_class_id, semester_id, name)
VALUES ('11111111-0000-0000-0000-000000000004','11111111-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000003','Course A');

INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-0000-0000-00000000000a','11111111-0000-0000-0000-000000000004','Project A');
INSERT INTO git_repo (id, project_id, provider, repository_id, full_name, connection_status, consecutive_failures, version, last_synced_at)
VALUES ('c0000000-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000a','GITHUB',555000111,'saga/repo-r','ACTIVE',0,0,'2026-09-01 10:00:00.000000');
INSERT INTO git_commit (id, repo_id, sha_hash, message, head_ref)
VALUES ('d0000000-0000-0000-0000-00000000000a','c0000000-0000-0000-0000-00000000000a','deadbeefcafefeed0001','Historical commit','main');
SQL

echo "== 3: apply V18 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== 4: existing GitRepo + GitCommit preserved; branch_membership_synced_at NULL =="
MYSQL -e "SELECT id, last_synced_at, branch_membership_synced_at FROM git_repo;"
MYSQL -e "SELECT id, repo_id, sha_hash, head_ref FROM git_commit;"

echo "== 5: SHOW CREATE TABLE =="
CREATE_REPO="$(MYSQL -N -e "SHOW CREATE TABLE git_repo;")"
echo "$CREATE_REPO"
echo "$CREATE_REPO" | grep -qi 'branch_membership_synced_at' || { echo "FAIL: branch_membership_synced_at missing"; exit 1; }
CREATE_MEM="$(MYSQL -N -e "SHOW CREATE TABLE git_commit_branch;")"
echo "$CREATE_MEM"
echo "$CREATE_MEM" | grep -qi 'utf8mb4_bin' || { echo "FAIL: utf8mb4_bin missing on branch_name"; exit 1; }
echo "$CREATE_MEM" | grep -qi 'uk_git_commit_branch' || { echo "FAIL: unique key missing"; exit 1; }
echo "$CREATE_MEM" | grep -qi 'ON DELETE CASCADE' || { echo "FAIL: cascade missing"; exit 1; }

echo "== 6: insert multi-branch membership =="
MYSQL <<'SQL'
INSERT INTO git_commit_branch (id, git_commit_id, branch_name)
VALUES
  ('e0000000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-00000000000a','develop'),
  ('e0000000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-00000000000a','release/v1'),
  ('e0000000-0000-0000-0000-000000000003','d0000000-0000-0000-0000-00000000000a','main');
SELECT branch_name FROM git_commit_branch ORDER BY branch_name;
SQL

echo "== 7: uniqueness (duplicate develop must fail) =="
set +e
MYSQL <<'SQL'
INSERT INTO git_commit_branch (id, git_commit_id, branch_name)
VALUES ('e0000000-0000-0000-0000-000000000099','d0000000-0000-0000-0000-00000000000a','develop');
SQL
STATUS=$?
set -e
if [ "$STATUS" -ne 0 ]; then
    echo "  -> expected: rejected by uk_git_commit_branch"
else
    echo "  -> UNEXPECTED: duplicate membership succeeded"
    exit 1
fi

echo "== 7b: case-sensitive refs (Develop vs develop both allowed) =="
MYSQL <<'SQL'
INSERT INTO git_commit_branch (id, git_commit_id, branch_name)
VALUES ('e0000000-0000-0000-0000-000000000004','d0000000-0000-0000-0000-00000000000a','Develop');
SQL

echo "== 8: cascade on GitCommit delete =="
MYSQL <<'SQL'
DELETE FROM git_commit WHERE id='d0000000-0000-0000-0000-00000000000a';
SELECT COUNT(*) AS leftover FROM git_commit_branch;
SQL
LEFTOVER="$(MYSQL -N -e "SELECT COUNT(*) FROM git_commit_branch;")"
if [ "$LEFTOVER" != "0" ]; then
    echo "FAIL: memberships not cascaded ($LEFTOVER leftover)"
    exit 1
fi

echo "== flyway validate =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"

echo "== second migrate -> expect 0 pending migrations =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" \
    | grep -i "up to date\|no migration necessary\|Successfully applied 0"

echo "== Hibernate schema validation =="
./mvnw.cmd test "-Dtest=com.saga.be.repository.V18MysqlHibernateValidateIT" "-Dsaga.verify.mysql=true" \
    "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
