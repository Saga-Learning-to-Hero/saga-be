package com.saga.be.entity.account;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.StudentInvitationStatus;
import com.saga.be.entity.enums.StudentInvitationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "student_course_invitation",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_invitation_student_course_type", columnNames = {"student_profile_id", "course_id", "invitation_type"})
	},
	indexes = {
		@Index(name = "ix_invitation_status", columnList = "invitation_status")
	}
)
public class StudentCourseInvitation extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_profile_id", nullable = false)
	private StudentProfile studentProfile;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@Enumerated(EnumType.STRING)
	@Column(name = "invitation_type", length = 32, nullable = false)
	private StudentInvitationType invitationType;

	@Enumerated(EnumType.STRING)
	@Column(name = "invitation_status", length = 32, nullable = false)
	private StudentInvitationStatus invitationStatus;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;

	@Column(name = "last_attempt_at")
	private LocalDateTime lastAttemptAt;

	@Column(name = "processing_started_at")
	private LocalDateTime processingStartedAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}
