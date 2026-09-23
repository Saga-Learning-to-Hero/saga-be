package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.AiRiskLevel;
import com.saga.be.entity.project.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_risk_analysis", uniqueConstraints = @UniqueConstraint(name = "uk_ai_risk_analysis_run", columnNames = "analysis_run_id"))
public class AiRiskAnalysis extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private Project project;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "analysis_run_id", nullable = false) private AiAnalysisRun analysisRun;
	@Enumerated(EnumType.STRING) @Column(name = "risk_level", length = 16, nullable = false) private AiRiskLevel riskLevel;
	@Column(name = "reasons_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String reasonsJson;
	@Column(name = "recommended_actions_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String recommendedActionsJson;
	@Column(name = "confidence") private Double confidence;
	@Column(name = "human_review_recommended", nullable = false) private boolean humanReviewRecommended;
}
