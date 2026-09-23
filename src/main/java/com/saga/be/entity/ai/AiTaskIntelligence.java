package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.AiTaskEvidenceStrength;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_task_intelligence", uniqueConstraints = @UniqueConstraint(name = "uk_ai_task_intelligence_run", columnNames = "analysis_run_id"))
public class AiTaskIntelligence extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private Project project;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "analysis_run_id", nullable = false) private AiAnalysisRun analysisRun;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id", nullable = false) private Task task;
	@Column(name = "task_revision", length = 128, nullable = false) private String taskRevision;
	@Enumerated(EnumType.STRING) @Column(name = "evidence_strength", length = 32, nullable = false) private AiTaskEvidenceStrength evidenceStrength;
	@Column(name = "summary", columnDefinition = "TEXT", nullable = false) private String summary;
	@Column(name = "deviation_detected", nullable = false) private boolean deviationDetected;
	@Column(name = "deviation_summary", columnDefinition = "TEXT") private String deviationSummary;
	@Column(name = "human_review_required", nullable = false) private boolean humanReviewRequired;
}
