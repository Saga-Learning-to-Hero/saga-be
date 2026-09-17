#!/usr/bin/env bash
# Disposable, no-install local MySQL 8.0.40 verification for V21
# (audit_log Project/Team write-time snapshots).
# Never point this at Railway/Aiven production.
set -euo pipefail

MYSQL_VERSION="8.0.40"
WORKDIR="$(mktemp -d)"
PORT=33066
DB=saga_v21_verify
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

echo "== 1: migrate V1 -> V20 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}" -Dflyway.target=20

echo "== 2: seed pre-V21 audit_log row (ids, no snapshots) =="
MYSQL <<'SQL'
INSERT INTO user_account
  (id, email, username, full_name, account_role, account_status, password_hash, created_at, updated_at)
VALUES ('aaaaaaaa-0000-4000-8000-000000000021','v21-audit@saga.local','v21audit','V21 Audit','ADMIN','ACTIVE','not-a-secret-in-api',NOW(6),NOW(6));
INSERT INTO audit_log
  (id, actor_user_id, actor_full_name_snapshot, context_project_id, context_team_id,
   action, entity_type, entity_id, source, occurred_at, created_at, updated_at)
VALUES ('bbbbbbbb-0000-4000-8000-000000000021','aaaaaaaa-0000-4000-8000-000000000021','V21 Audit',
        'cccccccc-0000-4000-8000-000000000021','dddddddd-0000-4000-8000-000000000021',
        'GITHUB_REPOSITORY_CONNECTED','git_repo','cccccccc-0000-4000-8000-000000000021','API',NOW(6),NOW(6),NOW(6));
SQL

echo "== 3: apply V21 =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:migrate" "${FLYWAY_ARGS[@]}"

echo "== 4: existing audit row survives with NULL snapshots =="
MYSQL -e "SELECT id, context_project_id, context_project_name_snapshot, context_team_id, context_team_no_snapshot, context_team_name_snapshot FROM audit_log;"
NAME_SNAP="$(MYSQL -N -e "SELECT IFNULL(context_project_name_snapshot,'') FROM audit_log WHERE id='bbbbbbbb-0000-4000-8000-000000000021';")"
TEAM_NO_SNAP="$(MYSQL -N -e "SELECT IFNULL(context_team_no_snapshot,'') FROM audit_log WHERE id='bbbbbbbb-0000-4000-8000-000000000021';")"
TEAM_NAME_SNAP="$(MYSQL -N -e "SELECT IFNULL(context_team_name_snapshot,'') FROM audit_log WHERE id='bbbbbbbb-0000-4000-8000-000000000021';")"
if [ -n "$NAME_SNAP" ] || [ -n "$TEAM_NO_SNAP" ] || [ -n "$TEAM_NAME_SNAP" ]; then
    echo "FAIL: pre-V21 row should have NULL snapshots, got name='$NAME_SNAP' teamNo='$TEAM_NO_SNAP' teamName='$TEAM_NAME_SNAP'"
    exit 1
fi

echo "== 5: SHOW CREATE TABLE / nullable columns =="
CREATE_AUDIT="$(MYSQL -N -e "SHOW CREATE TABLE audit_log;")"
echo "$CREATE_AUDIT"
echo "$CREATE_AUDIT" | grep -qi 'context_project_name_snapshot' || { echo "FAIL: project name snapshot missing"; exit 1; }
echo "$CREATE_AUDIT" | grep -qi 'context_team_no_snapshot' || { echo "FAIL: team no snapshot missing"; exit 1; }
echo "$CREATE_AUDIT" | grep -qi 'context_team_name_snapshot' || { echo "FAIL: team name snapshot missing"; exit 1; }

echo "== 6: flyway validate =="
./mvnw.cmd "org.flywaydb:flyway-maven-plugin:$FLYWAY_VERSION:validate" "${FLYWAY_ARGS[@]}"

echo "== 7: AuditLogV21MysqlIT =="
./mvnw.cmd test "-Dtest=com.saga.be.repository.AuditLogV21MysqlIT" "-Dsaga.verify.mysql=true" \
    "-Dsaga.verify.mysql.url=jdbc:mysql://127.0.0.1:$PORT/$DB"

echo "== done: mysqld will be stopped and the disposable datadir removed =="
