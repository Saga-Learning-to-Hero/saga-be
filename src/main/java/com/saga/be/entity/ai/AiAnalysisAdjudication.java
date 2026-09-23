package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.AiAdjudicationOutcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_analysis_adjudication", uniqueConstraints = @UniqueConstraint(name = "uk_ai_analysis_adjudication_run", columnNames = "analysis_run_id"))
public class AiAnalysisAdjudication extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "analysis_run_id", nullable = false) private AiAnalysisRun analysisRun;
	@Enumerated(EnumType.STRING) @Column(name = "outcome", length = 32, nullable = false) private AiAdjudicationOutcome outcome;
	@Column(name = "disagreement_details_json", columnDefinition = "MEDIUMTEXT") private String disagreementDetailsJson;
	@Column(name = "human_review_required", nullable = false) private boolean humanReviewRequired;
}
