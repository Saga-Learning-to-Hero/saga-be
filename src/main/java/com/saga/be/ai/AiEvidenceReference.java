package com.saga.be.ai;

import java.util.UUID;

public record AiEvidenceReference(AiEvidenceReferenceKind kind, UUID evidenceId, UUID repositoryId, String commitSha, String path, String rangeOrHunk, UUID taskId, String checkId, UUID syllabusVersionId, UUID syllabusNodeId) {}
