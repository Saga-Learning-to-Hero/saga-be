#!/usr/bin/env bash
# Disposable, no-install local MySQL 8.0.40 verification for V23
# (git_commit.parent_count nullable UNKNOWN). Never point this at production.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33068
DB=saga_v23_verify
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

echo "== 1: migrate V1 -> V22 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=22

echo "== 2: seed historical git_commit (pre-V23, no parent_count) =="
MYSQL <<'SQL'
INSERT INTO semester (id, code, name) VALUES ('11111111-0000-4000-8000-000000000023','FA24','Fall 2024');
INSERT INTO subject (id, subject_code, name, status) VALUES ('22222222-0000-4000-8000-000000000023','SWP391','Software Project','ACTIVE');
INSERT INTO academic_class (id, class_code, name, semester_id) VALUES ('33333333-0000-4000-8000-000000000023','SE1801','SE1801','11111111-0000-4000-8000-000000000023');
INSERT INTO course (id, subject_id, academic_class_id, semester_id, name)
VALUES ('44444444-0000-4000-8000-000000000023','22222222-0000-4000-8000-000000000023','33333333-0000-4000-8000-000000000023','11111111-0000-4000-8000-000000000023','Course A');
INSERT INTO project (id, course_id, name) VALUES ('a0000000-0000-4000-8000-000000000023','44444444-0000-4000-8000-000000000023','Project A');
INSERT INTO git_repo (id, project_id, provider, repository_id, full_name, connection_status, consecutive_failures, version)
VALUES ('c0000000-0000-4000-8000-000000000023','a0000000-0000-4000-8000-000000000023','GITHUB',555000123,'saga/repo-r','ACTIVE',0,0);
INSERT INTO git_commit (id, repo_id, sha_hash, message, head_ref)
VALUES ('d0000000-0000-4000-8000-000000000023','c0000000-0000-4000-8000-000000000023','deadbeefcafefeed0001','Historical commit','main');
SQL

echo "== 3: apply V23 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== 4: flyway validate (23 migrations) =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"
COUNT="$(MYSQL -N -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL;")"
if [ "$COUNT" != "23" ]; then
    echo "FAIL: expected 23 versioned migrations, got $COUNT"
    exit 1
fi

echo "== 5: SHOW CREATE TABLE git_commit =="
CREATE_COMMIT="$(MYSQL -N -e "SHOW CREATE TABLE git_commit;")"
echo "$CREATE_COMMIT"
echo "$CREATE_COMMIT" | grep -q 'parent_count' || { echo "FAIL: parent_count missing"; exit 1; }
echo "$CREATE_COMMIT" | grep -vi 'DEFAULT 1' | grep -q 'parent_count' || true
if echo "$CREATE_COMMIT" | grep -qi 'KEY `ix_git_commit_parent_count`'; then
    echo "FAIL: parent_count-only index present"
    exit 1
fi
NULLABLE="$(MYSQL -N -e "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='git_commit' AND column_name='parent_count';")"
if [ "$NULLABLE" != "YES" ]; then
    echo "FAIL: parent_count must be nullable, got $NULLABLE"
    exit 1
fi
HISTORICAL="$(MYSQL -N -e "SELECT IFNULL(parent_count,'NULL') FROM git_commit WHERE id='d0000000-0000-4000-8000-000000000023';")"
if [ "$HISTORICAL" != "NULL" ]; then
    echo "FAIL: historical row parent_count should be NULL, got $HISTORICAL"
    exit 1
fi

echo "== 5b: NULL -> 0/1/2 and webhook UNKNOWN does not revert known =="
MYSQL <<'SQL'
INSERT INTO git_commit (id, repo_id, sha_hash, message, head_ref) VALUES
('d0000000-0000-4000-8000-000000000024','c0000000-0000-4000-8000-000000000023','deadbeefcafefeed0002','root','main'),
('d0000000-0000-4000-8000-000000000025','c0000000-0000-4000-8000-000000000023','deadbeefcafefeed0003','normal','main'),
('d0000000-0000-4000-8000-000000000026','c0000000-0000-4000-8000-000000000023','deadbeefcafefeed0004','merge','main');
UPDATE git_commit SET parent_count = 0 WHERE id='d0000000-0000-4000-8000-000000000024';
UPDATE git_commit SET parent_count = 1 WHERE id='d0000000-0000-4000-8000-000000000025';
UPDATE git_commit SET parent_count = 2 WHERE id='d0000000-0000-4000-8000-000000000026';
UPDATE git_commit SET parent_count = 2 WHERE id='d0000000-0000-4000-8000-000000000023';
UPDATE git_commit SET parent_count = COALESCE(CAST(NULL AS SIGNED), parent_count)
WHERE id='d0000000-0000-4000-8000-000000000023';
SQL
ROOT="$(MYSQL -N -e "SELECT parent_count FROM git_commit WHERE id='d0000000-0000-4000-8000-000000000024';")"
NORMAL="$(MYSQL -N -e "SELECT parent_count FROM git_commit WHERE id='d0000000-0000-4000-8000-000000000025';")"
MERGE="$(MYSQL -N -e "SELECT parent_count FROM git_commit WHERE id='d0000000-0000-4000-8000-000000000026';")"
PRESERVED="$(MYSQL -N -e "SELECT parent_count FROM git_commit WHERE id='d0000000-0000-4000-8000-000000000023';")"
if [ "$ROOT" != "0" ] || [ "$NORMAL" != "1" ] || [ "$MERGE" != "2" ]; then
    echo "FAIL: expected parent_count 0/1/2, got $ROOT/$NORMAL/$MERGE"
    exit 1
fi
if [ "$PRESERVED" != "2" ]; then
    echo "FAIL: webhook UNKNOWN must not revert known parent_count, got $PRESERVED"
    exit 1
fi

echo "== 6: GitCommitParentCountV23MysqlIT + Hibernate validate =="
./mvnw.cmd test "-Dtest=com.saga.be.repository.GitCommitParentCountV23MysqlIT,com.saga.be.repository.V23MysqlHibernateValidateIT" "-Dsaga.verify.mysql=true" \
    "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
