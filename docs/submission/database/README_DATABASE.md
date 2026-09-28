# SAGA Database: Submission Notes

## 1. Database engine

**MySQL 8.4** (InnoDB, `utf8mb4`). Every table uses a `CHAR(36)` UUID primary key.
The one exception is `active_semester_setting`, which is a singleton keyed by `TINYINT`.

## 2. `schema_snapshot_v36.sql`

[`schema_snapshot_v36.sql`](schema_snapshot_v36.sql) is one consolidated SQL file. It holds the
**final** SAGA schema after Flyway migrations **V1 through V36**.

- It is not a concatenation of the migrations. Every later `ALTER`, `MODIFY`, `DROP INDEX` and
  `DROP CHECK` has been folded into the final `CREATE TABLE` statements, with columns in their
  final order. Superseded keys and constraints are left out, such as the V34 provider CHECKs
  that V36 replaced.
- Tables are created in dependency order. `FOREIGN_KEY_CHECKS` stays enabled throughout.
- A comment above each table records which migration created it and which migrations changed it.
- Final shape: 87 tables, 175 foreign keys, 30 CHECK constraints, 107 unique keys and 79
  secondary indexes. MySQL also creates the indexes that back foreign keys automatically.
- It includes the V35 AI retry lineage: `ai_analysis_run.canonical_identity_key`,
  `ai_analysis_run.retry_attempt` and `uk_ai_analysis_run_canonical_retry`.
- It includes the V36 provider set `OPENAI`, `GEMINI`, `OPENROUTER` and `COHERE` on all five
  provider CHECK constraints.

## 3. Authoritative migration history

`src/main/resources/db/migration/` (V1 to V36) is still the **authoritative** schema history that
the SAGA backend uses. Flyway applies it at startup, and the snapshot does not replace it.

The file `v4_delete_fk_subjectid_from_rubric_table.sql` in that folder does not follow the Flyway
naming convention. Flyway never applies it, so the snapshot does not include its changes.

## 4. Supported setup approaches

### A. Normal SAGA deployment (recommended)

1. Create an empty database, for example:
   `CREATE DATABASE saga CHARACTER SET utf8mb4;`
2. Point the backend's datasource configuration at it.
3. Start `saga-be`. Flyway applies V1 through V36 and records them in `flyway_schema_history`.

### B. University / manual inspection

1. Create an empty database, for example:
   `CREATE DATABASE saga_inspect CHARACTER SET utf8mb4;`
2. Import the snapshot:
   `mysql -u <user> -p saga_inspect < docs/submission/database/schema_snapshot_v36.sql`
3. Inspect the tables, keys and constraints with any MySQL client. The validation queries at the
   end of the SQL file return the expected counts.

The database from approach B has no `flyway_schema_history` table. Do not point the SAGA backend
at it with Flyway enabled, because Flyway would try to run V1 again on tables that already exist.

## 5. Never apply both approaches to the same database

Use **either** Flyway (approach A) **or** the snapshot (approach B) on a given empty database,
never both. Running the snapshot and then Flyway V1 to V36, or the reverse, fails with
"table already exists" errors.

## 6. No secrets or production data

The snapshot contains DDL only. The one exception is the small set of static system reference
rows that Flyway itself inserts into an empty database:

- the `active_semester_setting` singleton row (V1),
- the four canonical `project_type` catalog rows (V1),
- the four default peer-review `rubric_template` rows (V19).

It contains no users, projects, courses, OAuth tokens, API keys, encrypted credentials,
passwords, hostnames or `flyway_schema_history` rows. It also leaves out the one-time data
backfills from V5 to V35, because they do nothing on an empty database.

## 7. Demo / application data

If demo or application data is supplied for the submission, it comes as a **separate** artifact.
It is not part of this schema snapshot.
