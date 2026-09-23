package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_analysis_provider_decision", uniqueConstraints = @UniqueConstraint(name = "uk_ai_provider_decision_run_role_config", columnNames = {"analysis_run_id", "provider_role", "provider_config_hash"}), indexes = @Index(name = "ix_ai_provider_decision_run", columnList = "analysis_run_id"))
public class AiAnalysisProviderDecision extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "analysis_run_id", nullable = false) private AiAnalysisRun analysisRun;
	@Enumerated(EnumType.STRING) @Column(name = "provider_role", length = 32, nullable = false) private AiProviderRole providerRole;
	@Column(name = "provider_key", length = 64, nullable = false) private String providerKey;
	@Column(name = "provider_config_hash", length = 64, nullable = false) private String providerConfigHash;
	@Column(name = "model_id", length = 128, nullable = false) private String modelId;
	@Column(name = "model_revision", length = 128) private String modelRevision;
	@Enumerated(EnumType.STRING) @Column(name = "route", length = 32, nullable = false) private AiProviderRoute route;
	@Column(name = "started_at") private LocalDateTime startedAt;
	@Enumerated(EnumType.STRING) @Column(name = "status", length = 32, nullable = false) private AiProviderDecisionStatus status;
	@Column(name = "structured_result_json", columnDefinition = "MEDIUMTEXT") private String structuredResultJson;
	@Column(name = "schema_valid") private Boolean schemaValid;
	@Column(name = "latency_ms") private Long latencyMs;
	@Column(name = "input_units") private Long inputUnits;
	@Column(name = "output_units") private Long outputUnits;
	@Column(name = "cost_metadata_json", columnDefinition = "MEDIUMTEXT") private String costMetadataJson;
	@Column(name = "safe_error_code", length = 64) private String safeErrorCode;
	@Column(name = "completed_at") private LocalDateTime completedAt;
}
