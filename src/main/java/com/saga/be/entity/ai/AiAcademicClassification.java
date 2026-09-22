package com.saga.be.entity.ai;
import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.project.Project;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter;
@Getter @Setter @NoArgsConstructor @Entity @Table(name="ai_academic_classification")
public class AiAcademicClassification extends BaseEntity {
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="project_id") private Project project;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="analysis_run_id") private AiAnalysisRun analysisRun;
 @Enumerated(EnumType.STRING) @Column(name="artifact_type") private AiArtifactType artifactType;
 @Column(name="artifact_id") private UUID artifactId; @Column(name="artifact_revision") private String artifactRevision;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="syllabus_version_id") private SubjectSyllabusVersion syllabusVersion;
 @Enumerated(EnumType.STRING) @Column(name="target_type") private AiAcademicTargetType targetType;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="phase_id") private SyllabusPhase phase;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="deliverable_id") private SyllabusExpectedDeliverable deliverable;
 @Column(name="confidence") private Double confidence; @Column(name="ai_summary") private String aiSummary;
 @Enumerated(EnumType.STRING) @Column(name="status") private AiAcademicClassificationStatus status;
 @Enumerated(EnumType.STRING) @Column(name="provenance") private AiAcademicProvenance provenance;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="source_classification_id") private AiAcademicClassification sourceClassification;
 @Column(name="reviewed_at") private LocalDateTime reviewedAt;
}
