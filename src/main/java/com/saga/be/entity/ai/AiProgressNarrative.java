package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_progress_narrative", uniqueConstraints = @UniqueConstraint(name = "uk_ai_progress_narrative_run", columnNames = "analysis_run_id"))
public class AiProgressNarrative extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "analysis_run_id", nullable = false) private AiAnalysisRun analysisRun;
	@Column(name = "facts_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String factsJson;
	@Column(name = "overview", columnDefinition = "TEXT", nullable = false) private String overview;
	@Column(name = "highlights_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String highlightsJson;
	@Column(name = "concerns_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String concernsJson;
	@Column(name = "recommendations_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String recommendationsJson;
	@Column(name = "blockers_json", columnDefinition = "MEDIUMTEXT", nullable = false) private String blockersJson;
	@Column(name = "due_soon_overdue_note", columnDefinition = "TEXT", nullable = false) private String dueSoonOverdueNote;
	@Column(name = "human_review_recommended", nullable = false) private boolean humanReviewRecommended;
}
