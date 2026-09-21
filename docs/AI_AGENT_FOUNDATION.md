# AI-1 SAGA AI Agent Foundation

AI-1 is durable orchestration only. MySQL is authoritative; Neo4j and Redis are not AI-decision memory.

The V1 `ai_agent_*`, `commit_review_*`, and assessment tables are legacy scaffolding and are not read or written by AI-1. V27 adds immutable run, evidence, and provider-decision records.

An analysis identity is SHA-256 over project, artifact type/id/revision, analysis type, evidence hash, policy, prompt, schema, and provider configuration hash. Exact duplicates reuse the same row; changed evidence or configuration creates historical work.

Runs transition `QUEUED -> RUNNING -> COMPLETED|FAILED`. A scheduled recovery marks stale `RUNNING` rows as `FAILED/AI_RUNNING_STALE_RECOVERED` and re-enqueues persisted `QUEUED` rows. Queue rejection becomes `FAILED/AI_QUEUE_CAPACITY_EXCEEDED`.

AI-1 snapshots local commit and linked-task evidence only. It records that patches, changed paths, and file content are unavailable; it makes no GitHub request. It does not classify academics, create graph relations, send alerts, or judge code.

The provider contract treats commit messages, task descriptions, source code, comments, and documentation as untrusted data. Evidence must not contain credentials, authorization/session material, private keys, or `.env` content. The only provider is a local-profile deterministic fake provider for smoke testing; normal production profiles have no provider and do not require an AI key.
