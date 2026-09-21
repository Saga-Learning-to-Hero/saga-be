package com.saga.be.service.ai;

import com.saga.be.entity.enums.AiEvidenceType;

record AiEvidenceDraft(AiEvidenceType type, String sourceRef, String payloadJson, String metadataJson) {
	String contentHash() { return AiHashes.sha256((payloadJson == null ? "" : payloadJson) + "\n" + (metadataJson == null ? "" : metadataJson)); }
}
