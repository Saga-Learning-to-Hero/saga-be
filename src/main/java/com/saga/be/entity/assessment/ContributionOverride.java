package com.saga.be.entity.assessment;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.project.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "contribution_override",
	indexes = {
		@Index(name = "ix_contribution_override_course", columnList = "course_id")
	}
)
public class ContributionOverride extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "team_id", nullable = true)
	private Team team;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "student_profile_id", nullable = true)
	private StudentProfile studentProfile;

	@Column(name = "override_type", length = 64, nullable = false)
	private String overrideType;

	@Column(name = "old_value", precision = 10, scale = 4)
	private BigDecimal oldValue;

	@Column(name = "new_value", precision = 10, scale = 4)
	private BigDecimal newValue;

	@Column(name = "reason", columnDefinition = "TEXT")
	private String reason;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by_user_id", nullable = false)
	private UserAccount createdBy;
}
