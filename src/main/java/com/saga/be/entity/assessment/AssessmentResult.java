package com.saga.be.entity.assessment;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.assessment.AssessmentRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "assessment_result",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_assessment_result_run_student", columnNames = {"assessment_run_id", "student_profile_id"})
	}
)
public class AssessmentResult extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "assessment_run_id", nullable = false)
	private AssessmentRun assessmentRun;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_profile_id", nullable = false)
	private StudentProfile studentProfile;

	@Column(name = "contribution_score", precision = 10, scale = 4)
	private BigDecimal contributionScore;

	@Column(name = "peer_review_score", precision = 10, scale = 4)
	private BigDecimal peerReviewScore;

	@Column(name = "final_score", precision = 10, scale = 4)
	private BigDecimal finalScore;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "breakdown_json", columnDefinition = "json")
	private String breakdownJson;

	@Column(name = "calculated_at", nullable = false)
	private LocalDateTime calculatedAt;
}
