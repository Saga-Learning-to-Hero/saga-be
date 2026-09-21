package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.AiEvidenceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_analysis_evidence", uniqueConstraints = @UniqueConstraint(name = "uk_ai_analysis_evidence_run_ordinal", columnNames = {"analysis_run_id", "ordinal_index"}), indexes = @Index(name = "ix_ai_analysis_evidence_run_type", columnList = "analysis_run_id, evidence_type"))
public class AiAnalysisEvidence extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "analysis_run_id", nullable = false) private AiAnalysisRun analysisRun;
	@Enumerated(EnumType.STRING) @Column(name = "evidence_type", length = 64, nullable = false) private AiEvidenceType evidenceType;
	@Column(name = "source_ref", length = 512, nullable = false) private String sourceRef;
	@Column(name = "content_hash", length = 64, nullable = false) private String contentHash;
	@Column(name = "payload_json", columnDefinition = "MEDIUMTEXT") private String payloadJson;
	@Column(name = "metadata_json", columnDefinition = "MEDIUMTEXT") private String metadataJson;
	@Column(name = "ordinal_index", nullable = false) private int ordinalIndex;
}
