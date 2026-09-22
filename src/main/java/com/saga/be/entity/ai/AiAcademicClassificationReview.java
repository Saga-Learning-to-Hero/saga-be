package com.saga.be.entity.ai;
import com.saga.be.entity.BaseEntity; import com.saga.be.entity.account.UserAccount; import com.saga.be.entity.academic.SyllabusExpectedDeliverable; import com.saga.be.entity.academic.SyllabusPhase; import com.saga.be.entity.enums.*; import jakarta.persistence.*; import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter;
@Getter @Setter @NoArgsConstructor @Entity @Table(name="ai_academic_classification_review")
public class AiAcademicClassificationReview extends BaseEntity {
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="classification_id") private AiAcademicClassification classification;
 @Enumerated(EnumType.STRING) @Column(name="action") private AiAcademicReviewAction action;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="reviewer_user_id") private UserAccount reviewer;
 @Column(name="reason") private String reason;
 @Enumerated(EnumType.STRING) @Column(name="corrected_target_type") private AiAcademicTargetType correctedTargetType;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="corrected_phase_id") private SyllabusPhase correctedPhase;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="corrected_deliverable_id") private SyllabusExpectedDeliverable correctedDeliverable;
}
