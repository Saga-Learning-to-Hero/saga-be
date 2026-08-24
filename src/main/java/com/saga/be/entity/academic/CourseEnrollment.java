package com.saga.be.entity.academic;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.EnrollmentStatus;
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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "course_enrollment",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_enrollment_student_course", columnNames = {"student_profile_id", "course_id"}),
		@UniqueConstraint(name = "uk_enrollment_id_course", columnNames = {"id", "course_id"})
	},
	indexes = {
		@Index(name = "ix_enrollment_course", columnList = "course_id")
	}
)
public class CourseEnrollment extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_profile_id", nullable = false)
	private StudentProfile studentProfile;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@Enumerated(EnumType.STRING)
	@Column(name = "enrollment_status", length = 32, nullable = false)
	private EnrollmentStatus enrollmentStatus;

	@Column(name = "enrolled_at", nullable = false)
	private LocalDateTime enrolledAt;
}
